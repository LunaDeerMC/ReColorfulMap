"""Strict, read-only planning of Anvil region and external chunk changes.

The caller is responsible for locking worlds and committing the returned files
transactionally.  This module never writes to a region or external chunk file.
"""

from __future__ import annotations

import gzip
import re
import struct
import zlib
from dataclasses import dataclass
from pathlib import Path
from typing import Callable


SECTOR_BYTES = 4096
HEADER_BYTES = SECTOR_BYTES * 2
MAX_NBT_BYTES = 64 * 1024 * 1024
_REGION_NAME = re.compile(r"r\.(-?(?:0|[1-9][0-9]*))\.(-?(?:0|[1-9][0-9]*))\.mca\Z")


class RegionError(ValueError):
    """A region cannot safely be included in the migration."""


@dataclass(frozen=True)
class _Chunk:
    index: int
    context: str
    compression: int
    sectors: bytes
    nbt: bytes
    external_path: Path | None


def _read(path: Path) -> bytes:
    try:
        return path.read_bytes()
    except OSError as exc:
        raise RegionError(f"{path}: cannot read file: {exc}") from exc


def _decompress(payload: bytes, compression: int, context: str) -> bytes:
    if compression == 3:
        if len(payload) > MAX_NBT_BYTES:
            raise RegionError(f"{context}: uncompressed NBT exceeds the 64 MiB limit")
        return payload
    decoder = zlib.decompressobj(31 if compression == 1 else 15)
    try:
        result = decoder.decompress(payload, MAX_NBT_BYTES + 1)
    except zlib.error as exc:
        raise RegionError(f"{context}: invalid compressed chunk: {exc}") from exc
    if len(result) > MAX_NBT_BYTES or decoder.unconsumed_tail:
        raise RegionError(f"{context}: decompressed NBT exceeds the 64 MiB limit")
    if not decoder.eof:
        raise RegionError(f"{context}: truncated compressed chunk")
    if decoder.unused_data:
        raise RegionError(f"{context}: unexpected bytes after the compressed chunk")
    return result


def _compress(nbt: bytes, compression: int) -> bytes:
    if compression == 1:
        return gzip.compress(nbt, mtime=0)
    if compression == 2:
        return zlib.compress(nbt)
    return nbt


def rewrite_region(
    path: Path, transform: Callable[[bytes, str], bytes | None]
) -> dict[Path, bytes]:
    """Return modified file contents without writing anything.

    ``transform`` receives decompressed NBT and a descriptive chunk context. It
    returns replacement NBT, or ``None`` when no change is needed. All region
    locations, compression streams and external files are validated before any
    transform is invoked. Unmodified chunk records and all timestamps survive
    an internal rewrite byte for byte; free sectors may be removed.

    GZIP (1), ZLIB (2) and raw (3) chunks are supported, including their external
    ``c.<global_x>.<global_z>.mcc`` counterparts. LZ4Block and sector-count 255
    records are deliberately rejected, as are damaged or ambiguous regions.
    """
    path = Path(path)
    match = _REGION_NAME.fullmatch(path.name)
    if match is None or any(value == "-0" for value in match.groups()):
        raise RegionError(f"{path}: expected region filename r.<x>.<z>.mca")
    region_x, region_z = map(int, match.groups())
    original = _read(path)
    if len(original) < HEADER_BYTES:
        raise RegionError(f"{path}: truncated region header")
    if len(original) % SECTOR_BYTES:
        raise RegionError(f"{path}: region size is not a multiple of 4096 bytes")

    locations: list[tuple[int, int, int]] = []
    occupied: dict[int, int] = {}
    for index in range(1024):
        location = struct.unpack_from(">I", original, index * 4)[0]
        offset, count = location >> 8, location & 255
        if offset == 0 and count == 0:
            continue
        if offset == 0 or count == 0:
            raise RegionError(f"{path}: chunk slot {index} has a dirty location entry")
        if offset < 2:
            raise RegionError(f"{path}: chunk slot {index} overlaps the region header")
        if count == 255:
            raise RegionError(
                f"{path}: chunk slot {index}: unsupported 255-sector/oversized "
                "Spigot chunk; normalize the region before migration"
            )
        if (offset + count) * SECTOR_BYTES > len(original):
            raise RegionError(f"{path}: chunk slot {index} extends beyond the region file")
        for sector in range(offset, offset + count):
            if sector in occupied:
                raise RegionError(
                    f"{path}: chunk slots {occupied[sector]} and {index} "
                    f"overlap at sector {sector}"
                )
            occupied[sector] = index
        locations.append((index, offset, count))

    chunks: list[_Chunk] = []
    for index, offset, count in locations:
        global_x = region_x * 32 + index % 32
        global_z = region_z * 32 + index // 32
        context = f"{path}:chunk[{global_x},{global_z}]"
        sectors = original[offset * SECTOR_BYTES : (offset + count) * SECTOR_BYTES]
        length = struct.unpack_from(">I", sectors)[0]
        if length < 1:
            raise RegionError(f"{context}: chunk length does not contain a compression byte")
        if length + 4 > len(sectors):
            raise RegionError(f"{context}: chunk length exceeds its allocated sectors")
        version = sectors[4]
        compression = version & 0x7F
        if compression not in (1, 2, 3):
            name = " (Java LZ4Block)" if compression == 4 else ""
            raise RegionError(f"{context}: unsupported compression type {compression}{name}")
        external_path = None
        if version & 0x80:
            if length != 1:
                raise RegionError(f"{context}: external chunk record must have length 1")
            external_path = path.parent / f"c.{global_x}.{global_z}.mcc"
            payload = _read(external_path)
        else:
            payload = sectors[5 : 4 + length]
        nbt = _decompress(payload, compression, context)
        chunks.append(_Chunk(index, context, compression, sectors, nbt, external_path))

    changes: dict[Path, bytes] = {}
    replacements: dict[int, bytes] = {}
    for chunk in chunks:
        replacement = transform(chunk.nbt, chunk.context)
        if replacement is None:
            continue
        if not isinstance(replacement, bytes):
            raise TypeError(f"{chunk.context}: transform must return bytes or None")
        if replacement == chunk.nbt:
            continue
        if len(replacement) > MAX_NBT_BYTES:
            raise RegionError(f"{chunk.context}: replacement NBT exceeds the 64 MiB limit")
        payload = _compress(replacement, chunk.compression)
        if chunk.external_path is not None:
            changes[chunk.external_path] = payload
            continue
        record = struct.pack(">I", len(payload) + 1) + bytes([chunk.compression]) + payload
        count = (len(record) + SECTOR_BYTES - 1) // SECTOR_BYTES
        if count >= 255:
            raise RegionError(
                f"{chunk.context}: replacement would require an unsupported "
                "255-sector/oversized chunk"
            )
        replacements[chunk.index] = record.ljust(count * SECTOR_BYTES, b"\0")

    if replacements:
        header = bytearray(original[:HEADER_BYTES])
        result = bytearray(HEADER_BYTES)
        for chunk in chunks:
            record = replacements.get(chunk.index, chunk.sectors)
            offset = len(result) // SECTOR_BYTES
            count = len(record) // SECTOR_BYTES
            if offset > 0xFFFFFF:
                raise RegionError(f"{path}: rewritten region exceeds the location table capacity")
            struct.pack_into(">I", header, chunk.index * 4, (offset << 8) | count)
            result.extend(record)
        result[:HEADER_BYTES] = header
        changes[path] = bytes(result)
    return changes
