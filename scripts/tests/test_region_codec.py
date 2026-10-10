"""Synthetic region fixtures; these tests never access server world data."""

from __future__ import annotations

import gzip
import struct
import sys
import tempfile
import unittest
import zlib
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import region_codec
from region_codec import HEADER_BYTES, SECTOR_BYTES, RegionError, rewrite_region


def compress(raw: bytes, kind: int) -> bytes:
    return gzip.compress(raw, mtime=0) if kind == 1 else zlib.compress(raw) if kind == 2 else raw


def record(raw: bytes, kind: int, *, external: bool = False, padding: bytes = b"\0") -> bytes:
    payload = b"" if external else compress(raw, kind)
    value = struct.pack(">I", len(payload) + 1) + bytes([kind | (0x80 if external else 0)]) + payload
    count = (len(value) + SECTOR_BYTES - 1) // SECTOR_BYTES
    return value.ljust(count * SECTOR_BYTES, padding)


def region(records: dict[int, bytes]) -> bytes:
    header = bytearray(HEADER_BYTES)
    data = bytearray()
    # Keep an empty sector to verify safe compaction and location reconstruction.
    data.extend(b"F" * SECTOR_BYTES)
    for index, value in records.items():
        offset = 2 + len(data) // SECTOR_BYTES
        struct.pack_into(">I", header, index * 4, offset << 8 | len(value) // SECTOR_BYTES)
        struct.pack_into(">I", header, SECTOR_BYTES + index * 4, 1700000000 + index)
        data.extend(value)
    # Unused slot timestamps are valid and should survive rewrites too.
    struct.pack_into(">I", header, SECTOR_BYTES + 1000 * 4, 123456)
    return bytes(header + data)


def get_record(data: bytes, index: int) -> bytes:
    location = struct.unpack_from(">I", data, index * 4)[0]
    offset, count = location >> 8, location & 255
    return data[offset * SECTOR_BYTES : (offset + count) * SECTOR_BYTES]


class RegionCodecTests(unittest.TestCase):
    def setUp(self) -> None:
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.path = Path(self.directory.name) / "r.-2.3.mca"

    def write(self, records: dict[int, bytes]) -> bytes:
        data = region(records)
        self.path.write_bytes(data)
        return data

    def assert_bad(self, data: bytes, pattern: str) -> None:
        self.path.write_bytes(data)
        calls = []
        with self.assertRaisesRegex(RegionError, pattern):
            rewrite_region(self.path, lambda raw, context: calls.append(raw))
        self.assertEqual(calls, [], "invalid regions must fail before transformation")

    def test_each_compression_and_unchanged_records_and_timestamps(self) -> None:
        for kind in (1, 2, 3):
            with self.subTest(compression=kind):
                untouched = record(b"untouched", 2, padding=b"X")
                original = self.write({0: record(b"old", kind), 33: untouched})
                contexts = []

                def transform(raw: bytes, context: str) -> bytes | None:
                    contexts.append(context)
                    return b"new" * 3000 if raw == b"old" else None

                changes = rewrite_region(self.path, transform)
                self.assertEqual(set(changes), {self.path})
                output = changes[self.path]
                self.assertEqual(output[SECTOR_BYTES:HEADER_BYTES], original[SECTOR_BYTES:HEADER_BYTES])
                self.assertEqual(get_record(output, 33), untouched)
                self.assertIn("chunk[-64,96]", contexts[0])
                self.assertIn("chunk[-63,97]", contexts[1])
                self.assertEqual(self.path.read_bytes(), original)
                self.path.write_bytes(output)
                decoded = []
                self.assertEqual(rewrite_region(self.path, lambda raw, _: decoded.append(raw)), {})
                self.assertEqual(decoded, [b"new" * 3000, b"untouched"])

    def test_external_each_compression_updates_only_mcc(self) -> None:
        for kind in (1, 2, 3):
            with self.subTest(compression=kind):
                original = self.write({63: record(b"", kind, external=True)})
                external = self.path.parent / "c.-33.97.mcc"
                old = compress(b"old external", kind)
                external.write_bytes(old)
                changes = rewrite_region(self.path, lambda raw, _: b"new external")
                self.assertEqual(changes, {external: compress(b"new external", kind)})
                self.assertEqual(self.path.read_bytes(), original)
                self.assertEqual(external.read_bytes(), old)

    def test_external_stub_preserved_when_internal_chunk_changes(self) -> None:
        external_record = record(b"", 2, external=True, padding=b"X")
        self.write({0: record(b"old", 3), 1: external_record})
        external = self.path.parent / "c.-63.96.mcc"
        external.write_bytes(compress(b"external", 2))
        changes = rewrite_region(self.path, lambda raw, _: b"new" if raw == b"old" else None)
        self.assertEqual(set(changes), {self.path})
        self.assertEqual(get_record(changes[self.path], 1), external_record)

    def test_no_changes_including_identical_replacement_returns_empty(self) -> None:
        self.write({0: record(b"old", 2)})
        self.assertEqual(rewrite_region(self.path, lambda raw, _: None), {})
        self.assertEqual(rewrite_region(self.path, lambda raw, _: raw), {})

    def test_empty_region(self) -> None:
        self.path.write_bytes(bytes(HEADER_BYTES))
        self.assertEqual(rewrite_region(self.path, lambda raw, _: raw), {})

    def test_dirty_location(self) -> None:
        for location in (1, 2 << 8):
            with self.subTest(location=location):
                data = bytearray(HEADER_BYTES)
                struct.pack_into(">I", data, 0, location)
                self.assert_bad(bytes(data), "dirty location")

    def test_header_overlap(self) -> None:
        data = bytearray(HEADER_BYTES)
        struct.pack_into(">I", data, 0, 1 << 8 | 1)
        self.assert_bad(bytes(data), "overlaps the region header")

    def test_out_of_bounds(self) -> None:
        data = bytearray(HEADER_BYTES)
        struct.pack_into(">I", data, 0, 2 << 8 | 1)
        self.assert_bad(bytes(data), "beyond the region file")

    def test_shared_sectors(self) -> None:
        data = bytearray(region({0: record(b"valid", 3)}))
        data[4:8] = data[:4]
        self.assert_bad(bytes(data), "overlap at sector")

    def test_truncated_header_and_unaligned_file(self) -> None:
        self.assert_bad(bytes(100), "truncated region header")
        self.assert_bad(bytes(HEADER_BYTES + 1), "multiple of 4096")

    def test_invalid_lengths(self) -> None:
        for length in (0, SECTOR_BYTES):
            with self.subTest(length=length):
                chunk = bytearray(record(b"valid", 3))
                struct.pack_into(">I", chunk, 0, length)
                self.assert_bad(region({0: bytes(chunk)}), "chunk length")

    def test_unknown_compression_and_lz4(self) -> None:
        for kind in (0, 4, 5, 127, 132):
            with self.subTest(compression=kind):
                chunk = bytearray(record(b"raw", 3))
                chunk[4] = kind
                self.assert_bad(region({0: bytes(chunk)}), "unsupported compression")

    def test_bad_later_chunk_prevents_earlier_transform(self) -> None:
        invalid = bytearray(record(b"raw", 3))
        invalid[4] = 4
        self.assert_bad(region({0: record(b"valid", 2), 1: bytes(invalid)}), "Java LZ4Block")

    def test_255_sector_record_rejected_explicitly(self) -> None:
        data = bytearray(HEADER_BYTES)
        struct.pack_into(">I", data, 0, 2 << 8 | 255)
        self.assert_bad(bytes(data), "unsupported 255-sector/oversized Spigot")

    def test_missing_external_file(self) -> None:
        self.assert_bad(region({0: record(b"", 2, external=True)}), "cannot read file")

    def test_external_stub_payload_rejected(self) -> None:
        chunk = bytearray(record(b"raw", 3))
        chunk[4] |= 0x80
        self.assert_bad(region({0: bytes(chunk)}), "must have length 1")

    def test_invalid_and_truncated_compression(self) -> None:
        for kind in (1, 2):
            with self.subTest(compression=kind):
                for payload in (b"broken", compress(b"raw", kind)[:-1]):
                    chunk = struct.pack(">I", len(payload) + 1) + bytes([kind]) + payload
                    self.assert_bad(region({0: chunk.ljust(SECTOR_BYTES, b"\0")}), "compressed chunk")

    def test_trailing_compression_bytes_rejected(self) -> None:
        for kind in (1, 2):
            with self.subTest(compression=kind):
                payload = compress(b"valid", kind) + b"trailing"
                chunk = struct.pack(">I", len(payload) + 1) + bytes([kind]) + payload
                self.assert_bad(region({0: chunk.ljust(SECTOR_BYTES, b"\0")}), "unexpected bytes")

    def test_decompression_and_raw_limit(self) -> None:
        with patch.object(region_codec, "MAX_NBT_BYTES", 100):
            for kind in (1, 2, 3):
                with self.subTest(compression=kind):
                    self.assert_bad(region({0: record(b"X" * 101, kind)}), "64 MiB limit")

    def test_exact_decompression_limit_allowed(self) -> None:
        with patch.object(region_codec, "MAX_NBT_BYTES", 100):
            for kind in (1, 2, 3):
                with self.subTest(compression=kind):
                    self.write({0: record(b"X" * 100, kind)})
                    self.assertEqual(rewrite_region(self.path, lambda raw, _: raw), {})

    def test_replacement_limit(self) -> None:
        self.write({0: record(b"old", 3)})
        with patch.object(region_codec, "MAX_NBT_BYTES", 100):
            with self.assertRaisesRegex(RegionError, "replacement NBT"):
                rewrite_region(self.path, lambda raw, _: b"X" * 101)

    def test_internal_replacement_does_not_silently_overflow_sector_count(self) -> None:
        self.write({0: record(b"old", 3)})
        with self.assertRaisesRegex(RegionError, "unsupported.*255-sector/oversized"):
            rewrite_region(self.path, lambda raw, _: b"X" * (254 * SECTOR_BYTES))

    def test_reject_non_bytes_transform(self) -> None:
        self.write({0: record(b"old", 3)})
        with self.assertRaisesRegex(TypeError, "bytes or None"):
            rewrite_region(self.path, lambda raw, _: "new")

    def test_invalid_region_name(self) -> None:
        for name in ("r.x.0.mca", "r.01.0.mca", "r.-0.0.mca", "r.0.0.mca.bak"):
            with self.subTest(name=name):
                with self.assertRaisesRegex(RegionError, "expected region filename"):
                    rewrite_region(self.path.parent / name, lambda raw, _: None)


if __name__ == "__main__":
    unittest.main()
