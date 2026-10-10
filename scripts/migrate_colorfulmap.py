#!/usr/bin/env python3
"""Offline ColorfulMap migration. Run against a stopped Java server, never live data.

Artwork directories are copied to the new plugin directory. Every stored map item gets
its own small path PDC; the old map-ID index is used solely by this tool.
"""
from __future__ import annotations

import argparse
import contextlib
import gzip
import hashlib
import json
import os
from pathlib import Path
import re
import struct
import sys
import tempfile
import uuid
import zlib
from dataclasses import dataclass, field
from datetime import datetime, timezone

import yaml

from nbt_codec import (Document, NBTError, Tag, compound, dump, int_value,
                       parse, string, string_value)
from region_codec import RegionError, rewrite_region


PDC_KEY = "re_colorful_map:image_path"
FILLED_MAP_ID = string("minecraft:filled_map").value
UUID_TEXT = r"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"
IMAGE_PATH = re.compile(r"maps/(" + UUID_TEXT + r")/(thumb\.png|(?:0|[1-9][0-9]*)_(?:0|[1-9][0-9]*)\.png)\Z")
TILE_NAME = re.compile(r"(0|[1-9][0-9]*)_(0|[1-9][0-9]*)\.png\Z")
MAP_FILE = re.compile(r"map_([0-9]+)\.dat\Z")
MODERN_MAP_FILE = re.compile(r"([0-9]+)\.dat\Z")
MAX_NBT = 64 * 1024 * 1024
TRANSACTION_DIR = ".recolorfulmap-migrations"


class MigrationError(ValueError):
    pass


class UniqueLoader(yaml.SafeLoader):
    pass


def unique_mapping(loader, node, deep=False):
    result = {}
    for key_node, value_node in node.value:
        key = loader.construct_object(key_node, deep=deep)
        if key in result:
            raise MigrationError(f"Duplicate YAML key: {key!r}")
        result[key] = loader.construct_object(value_node, deep=deep)
    return result


UniqueLoader.add_constructor(yaml.resolver.BaseResolver.DEFAULT_MAPPING_TAG, unique_mapping)


def digest(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def read_regular(path: Path) -> bytes:
    if path.is_symlink() or not path.is_file():
        raise MigrationError(f"Expected a regular file (no symlinks): {path}")
    return path.read_bytes()


def no_symlink_parents(path: Path) -> None:
    for part in (path, *path.parents):
        if part.is_symlink():
            raise MigrationError(f"Symbolic links are not supported: {part}")


def rooted(root: Path, value: str | Path) -> Path:
    path = Path(value)
    path = path if path.is_absolute() else root / path
    no_symlink_parents(path.absolute())
    return path.resolve()


def decode_dat(data: bytes) -> tuple[Document, str]:
    if data.startswith(b"\x1f\x8b"):
        codec, window = "gzip", 31
    elif len(data) >= 2 and data[0] == 0x78 and ((data[0] << 8) + data[1]) % 31 == 0:
        codec, window = "zlib", 15
    else:
        if len(data) > MAX_NBT:
            raise MigrationError("NBT file exceeds 64 MiB limit")
        return parse(data), "raw"
    decoder = zlib.decompressobj(window)
    raw = decoder.decompress(data, MAX_NBT + 1)
    if len(raw) > MAX_NBT or decoder.unconsumed_tail or not decoder.eof or decoder.unused_data:
        raise MigrationError("Invalid, oversized, or trailing compressed NBT data")
    return parse(raw), codec


def encode_dat(document: Document, codec: str) -> bytes:
    raw = dump(document)
    if codec == "gzip":
        return gzip.compress(raw, mtime=0)
    if codec == "zlib":
        return zlib.compress(raw)
    return raw


def require_compound(parent: Tag, key: str, create: bool = False) -> Tag | None:
    child = parent.get(key)
    if child is None and create:
        child = compound()
        parent.set(key, child)
    if child is not None and child.kind != 10:
        raise MigrationError(f"Expected Compound tag: {key}")
    return child


def checked_image_path(value: str) -> str:
    if not isinstance(value, str) or not IMAGE_PATH.fullmatch(value):
        raise MigrationError(f"Unsafe or unsupported image path: {value!r}")
    return value


def load_index(source: Path) -> tuple[Path, dict[tuple[str, int], str], bytes]:
    files = [source / "maps" / name for name in ("maps.yml", "maps.yaml")]
    found = [path for path in files if path.exists()]
    if len(found) != 1:
        raise MigrationError(f"Expected exactly one old maps.yml/maps.yaml under {source / 'maps'}")
    index = found[0]
    original = read_regular(index)
    data = yaml.load(original.decode("utf-8"), Loader=UniqueLoader)
    if not isinstance(data, dict):
        raise MigrationError(f"Invalid map index: {index}")
    result = {}
    for world, entries in data.items():
        if not isinstance(world, str) or not world or not isinstance(entries, dict):
            raise MigrationError(f"Invalid world section: {world!r}")
        for identifier, path in entries.items():
            if isinstance(identifier, bool) or not re.fullmatch(r"0|[1-9][0-9]*", str(identifier)):
                raise MigrationError(f"Invalid map ID: {identifier!r}")
            number = int(identifier)
            if number > 2147483647:
                raise MigrationError(f"Map ID out of range: {identifier}")
            key = (world, number)
            if key in result:
                raise MigrationError(f"Duplicate map ID: {key}")
            result[key] = checked_image_path(path)
    return index, result, original


def discover_worlds(root: Path, overrides: list[str]) -> dict[str, Path]:
    worlds = {}
    excluded = {"plugins", "libraries", "versions", "cache", "logs", "backups", "scripts", ".git", TRANSACTION_DIR}
    for directory, names, files in os.walk(root, followlinks=False):
        names[:] = sorted(n for n in names if n not in excluded and not (Path(directory) / n).is_symlink())
        if "level.dat" in files:
            path = Path(directory)
            if path.name in worlds and worlds[path.name] != path:
                raise MigrationError(f"Duplicate world directory name {path.name}; use --world to specify world paths")
            worlds[path.name] = path
            names.clear()
    for override in overrides:
        if "=" not in override:
            raise MigrationError("--world must be NAME=PATH")
        name, value = override.split("=", 1)
        if not name or not value:
            raise MigrationError("--world must be NAME=PATH")
        path = rooted(root, value)
        if not (path / "level.dat").is_file():
            raise MigrationError(f"World has no level.dat: {path}")
        # An explicit Bukkit name replaces the directory-name discovery alias.
        worlds = {key: old for key, old in worlds.items() if old != path}
        worlds[name] = path
    if not worlds:
        raise MigrationError("No worlds found; pass --world NAME=PATH for an external world directory")
    return worlds


@contextlib.contextmanager
def world_locks(worlds: dict[str, Path]):
    try:
        import fcntl
    except ImportError as error:
        raise MigrationError("This migration requires POSIX file locks; run it on Linux/macOS or WSL with the server stopped") from error
    handles = []
    try:
        for directory in sorted(set(worlds.values())):
            path = directory / "session.lock"
            if path.is_symlink() or not path.is_file():
                raise MigrationError(f"Missing world session.lock: {path}; stop the server after saving this world")
            handle = path.open("r+b")
            handles.append(handle)
            try:
                fcntl.lockf(handle, fcntl.LOCK_EX | fcntl.LOCK_NB)
            except OSError as error:
                raise MigrationError(f"World is locked by a running server or migration: {directory}") from error
        yield
    finally:
        for handle in reversed(handles):
            handle.close()


def stored_files(worlds: dict[str, Path]) -> tuple[list[Path], list[Path]]:
    dat_files, regions = set(), set()
    for world in worlds.values():
        for directory, names, files in os.walk(world, followlinks=False):
            base = Path(directory)
            if any((base / name).is_symlink() for name in names + files):
                raise MigrationError(f"World contains a symbolic link; resolve it before migration: {base}")
            for name in files:
                path = base / name
                # 26.1+ namespaces SavedData under data/<namespace>/; older
                # versions keep map_*.dat directly in data/. Cover both.
                relative_parents = base.relative_to(world).parts
                if name == "level.dat" and base == world or name.endswith(".dat") and any(part in {"data", "playerdata"} for part in relative_parents):
                    dat_files.add(path)
                elif name.endswith(".mca") and base.name in {"region", "entities"}:
                    regions.add(path)
                elif "oversized" in name.lower() and base.name in {"region", "entities"}:
                    raise MigrationError(f"Legacy Paper oversized region storage is not supported: {path}")
    return sorted(dat_files), sorted(regions)


class Resolver:
    def __init__(self, index: dict[tuple[str, int], str], worlds: dict[str, Path], dat_files: list[Path], plan: Plan | None = None,
                 dimension_overrides: dict[Path, str] | None = None):
        self.index = index
        self.used: set[tuple[str, int]] = set()
        self.by_id: dict[int, dict[str, str]] = {}
        for (world, identifier), path in index.items():
            self.by_id.setdefault(identifier, {})[world] = path
        uid_names = {}
        name_uids = {}
        dimension_names: dict[str, set[str]] = {}
        unknown_dimensions = set()
        unknown_identities = set()
        used_overrides = set()
        dimension_overrides = dimension_overrides or {}

        def register_identity(identity: uuid.UUID, name: str):
            if identity in unknown_identities:
                raise MigrationError(f"Duplicate Bukkit world UUID: {identity}")
            previous = uid_names.get(identity)
            if previous is not None and previous != name:
                raise MigrationError(f"Duplicate Bukkit world UUID: {name} / {previous}")
            previous_identity = name_uids.get(name)
            if previous_identity is not None and previous_identity != identity:
                raise MigrationError(f"Conflicting Bukkit UUIDs for world {name}: {previous_identity} / {identity}")
            uid_names[identity] = name
            name_uids[name] = identity

        for name, directory in worlds.items():
            uid_file = directory / "uid.dat"
            if uid_file.exists():
                raw = plan.read(uid_file) if plan is not None else read_regular(uid_file)
                if len(raw) != 16:
                    raise MigrationError(f"Invalid Bukkit world UUID file: {uid_file}")
                identity = uuid.UUID(bytes=raw)
                register_identity(identity, name)
            # Paper 26.2 stores each dimension's UUID in namespaced SavedData,
            # replacing uid.dat. Bukkit names below match PaperWorldLoader's
            # defaultWorldName(levelId, stemKey), not the dimension folder name.
            dimensions = directory / "dimensions"
            if dimensions.is_dir():
                for metadata in sorted(dimensions.glob("**/data/paper/metadata.dat")):
                    parts = metadata.relative_to(dimensions).parts
                    if len(parts) < 5:
                        raise MigrationError(f"Invalid Paper dimension metadata location: {metadata}")
                    namespace, dimension_path = parts[0], "/".join(parts[1:-3])
                    key = namespace + ":" + dimension_path
                    conventional = {"minecraft:overworld": name, "minecraft:the_nether": name + "_nether", "minecraft:the_end": name + "_the_end"}
                    dimension_directory = metadata.parent.parent.parent
                    dimension_name = dimension_overrides.get(dimension_directory, conventional.get(key))
                    if dimension_directory in dimension_overrides:
                        used_overrides.add(dimension_directory)
                    document, _ = decode_dat(plan.read(metadata) if plan is not None else read_regular(metadata))
                    data = require_compound(document.root, "data")
                    identity_tag = data.get("uuid") if data is not None else None
                    if identity_tag is None or identity_tag.kind != 11 or len(identity_tag.value) != 20 or identity_tag.value[:4] != b"\x00\x00\x00\x04":
                        raise MigrationError(f"Expected UUID int-array[4] in Paper metadata: {metadata}")
                    identity = uuid.UUID(bytes=identity_tag.value[4:])
                    if dimension_name is None:
                        # API-created worlds accept arbitrary WorldCreator names;
                        # Paper saves only their UUID, not that Bukkit name.
                        # The same key also has a different default datapack name.
                        # An explicit name is needed to disambiguate custom worlds.
                        if identity in uid_names or identity in unknown_identities:
                            raise MigrationError(f"Duplicate Bukkit world UUID in metadata: {metadata}")
                        unknown_identities.add(identity)
                        unknown_dimensions.add(key)
                    else:
                        register_identity(identity, dimension_name)
                        dimension_names.setdefault(key, set()).add(dimension_name)
        if used_overrides != dimension_overrides.keys():
            raise MigrationError(f"--dimension does not identify discovered Paper metadata: {sorted(str(path) for path in dimension_overrides.keys() - used_overrides)}")
        self.native: dict[int, set[str]] = {}
        self.native_unknown: set[int] = set()
        for path in dat_files:
            match = MAP_FILE.fullmatch(path.name)
            if match is None and tuple(path.parts[-4:-1]) == ("data", "minecraft", "maps"):
                # Since 26.1 MapId.key() is minecraft:maps/<number>.
                match = MODERN_MAP_FILE.fullmatch(path.name)
            if match is None or int(match[1]) not in self.by_id:
                continue
            identifier = int(match[1])
            document, _ = decode_dat(plan.read(path) if plan is not None else read_regular(path))
            data = require_compound(document.root, "data")
            if data is None:
                raise MigrationError(f"Native map has no data Compound: {path}")
            most, least = data.get("UUIDMost"), data.get("UUIDLeast")
            name = None
            if (most is None) != (least is None):
                raise MigrationError(f"Incomplete Bukkit world UUID in native map data: {path}")
            if most is not None and least is not None:
                identity = uuid.UUID(int=((int_value(most) & ((1 << 64) - 1)) << 64) | (int_value(least) & ((1 << 64) - 1)))
                name = uid_names.get(identity)
            # A stored Bukkit UUID is stronger evidence than the vanilla
            # dimension name, which can be shared by multiple Bukkit worlds.
            has_uuid = most is not None and least is not None
            if name is None and not has_uuid:
                dimension = data.get("dimension")
                if dimension is not None and dimension.kind == 8:
                    dimension_name = string_value(dimension)
                    # An unnamed custom world sharing this dimension key makes
                    # a dimension-only match ambiguous even if one other world
                    # has an explicit name. Never select that named world alone.
                    candidates = [] if dimension_name in unknown_dimensions else list(dimension_names.get(dimension_name, ()))
                    if not candidates and dimension_name not in unknown_dimensions:
                        candidates = [world for world in worlds if dimension_name == "minecraft:" + world.lower()]
                        conventional = {"minecraft:overworld": "", "minecraft:the_nether": "_nether", "minecraft:the_end": "_the_end"}
                        if dimension_name in conventional:
                            suffix = conventional[dimension_name]
                            candidates = [world for world in worlds if (world.endswith(suffix) if suffix else not world.endswith(("_nether", "_the_end")))]
                    if len(candidates) == 1:
                        name = candidates[0]
            if name is None:
                self.native_unknown.add(identifier)
            else:
                self.native.setdefault(identifier, set()).add(name)

    def resolve(self, identifier: int) -> str | None:
        options = self.by_id.get(identifier)
        if options is None:
            return None
        if identifier in self.native_unknown:
            raise MigrationError(f"Map ID {identifier} has native map data but its source world cannot be identified; include its uid.dat/Paper metadata, --world NAME=PATH, or --dimension BUKKIT_NAME=world/dimensions/namespace/path for a custom 26.2 dimension")
        native = self.native.get(identifier, set())
        if native:
            paths = {options[name] for name in native if name in options}
            if len(native) > 1 and (len(paths) != 1 or any(name not in options for name in native)):
                raise MigrationError(f"Map ID {identifier} has conflicting native world identities: {sorted(native)}")
            if not paths:
                return None  # A stale index entry must not turn a vanilla map into artwork.
            result = next(iter(paths))
            self.used.update((name, identifier) for name in native if name in options)
            return result
        paths = set(options.values())
        if len(paths) != 1:
            raise MigrationError(f"Map ID {identifier} is ambiguous across worlds {sorted(options)}; restore native map data and world uid.dat files")
        self.used.update((name, identifier) for name in options)
        return next(iter(paths))


@dataclass
class Counts:
    migrated: int = 0
    already: int = 0
    vanilla: int = 0


def migrate_document(document: Document, resolver: Resolver, counts: Counts) -> bool:
    changed = False

    def visit(tag: Tag):
        nonlocal changed
        if tag.kind == 10:
            identifier_tag = tag.get("id")
            # Unknown custom data can also have an `id` key containing Java
            # Modified UTF-8. Match this ASCII identifier without decoding any
            # unrelated string, preserving emoji/NUL and arbitrary plugin data.
            if identifier_tag is not None and identifier_tag.kind == 8 and identifier_tag.value == FILLED_MAP_ID:
                modern = tag.get("components") is not None or tag.get("count") is not None
                container = require_compound(tag, "components" if modern else "tag")
                map_tag = container.get("minecraft:map_id" if modern else "map") if container is not None else None
                if map_tag is not None:
                    reference = resolver.resolve(int_value(map_tag))
                    custom = require_compound(container, "minecraft:custom_data") if modern else container
                    pdc = require_compound(custom, "PublicBukkitValues") if custom is not None else None
                    existing = pdc.get(PDC_KEY) if pdc is not None else None
                    if existing is not None:
                        existing_path = checked_image_path(string_value(existing))
                        if reference is not None and existing_path != reference:
                            raise MigrationError(f"Existing PDC conflicts with legacy map ID {int_value(map_tag)}: {existing_path} / {reference}")
                        counts.already += 1
                    elif reference is None:
                        counts.vanilla += 1
                    else:
                        custom = require_compound(container, "minecraft:custom_data", True) if modern else container
                        pdc = require_compound(custom, "PublicBukkitValues", True)
                        pdc.set(PDC_KEY, string(reference))
                        counts.migrated += 1
                        changed = True
            # Materialize children because item migration can append a new child.
            for child in list(tag.children()):
                visit(child)
        elif tag.kind == 9:
            for child in tag.children():
                visit(child)

    visit(document.root)
    return changed


def validate_png(path: Path, data: bytes) -> None:
    if not data.startswith(b"\x89PNG\r\n\x1a\n"):
        raise MigrationError(f"Invalid PNG: {path}")
    offset, saw_header, saw_end = 8, False, False
    pixel_data = bytearray()
    palette = None
    depth = color = interlace = 0
    while offset < len(data):
        if offset + 12 > len(data):
            raise MigrationError(f"Truncated PNG: {path}")
        length = struct.unpack_from(">I", data, offset)[0]
        end = offset + length + 12
        if end > len(data):
            raise MigrationError(f"Truncated PNG chunk: {path}")
        kind = data[offset + 4:offset + 8]
        payload = data[offset + 8:end - 4]
        crc = struct.unpack_from(">I", data, end - 4)[0]
        if zlib.crc32(kind + payload) & 0xffffffff != crc:
            raise MigrationError(f"PNG checksum mismatch: {path}")
        if not saw_header:
            if kind != b"IHDR" or length != 13 or struct.unpack(">II", payload[:8]) != (128, 128):
                raise MigrationError(f"Map PNG must be 128x128: {path}")
            depth, color, compression, filtering, interlace = payload[8:]
            allowed = {0: {1, 2, 4, 8, 16}, 2: {8, 16}, 3: {1, 2, 4, 8}, 4: {8, 16}, 6: {8, 16}}
            if depth not in allowed.get(color, set()) or compression or filtering or interlace not in {0, 1}:
                raise MigrationError(f"Unsupported PNG header: {path}")
            saw_header = True
        elif kind == b"IHDR":
            raise MigrationError(f"Duplicate PNG header: {path}")
        if kind == b"PLTE":
            if palette is not None or pixel_data or not length or length % 3 or length > 768:
                raise MigrationError(f"Invalid PNG palette: {path}")
            palette = length // 3
        if kind == b"IEND":
            saw_end = True
            if length != 0 or end != len(data):
                raise MigrationError(f"Invalid PNG ending: {path}")
        elif kind == b"IDAT":
            pixel_data.extend(payload)
        offset = end
    if not saw_header or not saw_end or not pixel_data:
        raise MigrationError(f"Incomplete PNG: {path}")
    if color == 3 and (palette is None or palette > 1 << depth):
        raise MigrationError(f"Missing or invalid indexed PNG palette: {path}")
    try:
        decoder = zlib.decompressobj()
        pixels = decoder.decompress(pixel_data, 2 * 1024 * 1024)
        if not pixels or not decoder.eof or decoder.unused_data or decoder.unconsumed_tail:
            raise MigrationError(f"Invalid PNG image data: {path}")
    except zlib.error as error:
        raise MigrationError(f"Invalid PNG image data: {path}") from error
    channels = {0: 1, 2: 3, 3: 1, 4: 2, 6: 4}[color]
    passes = [(0, 0, 1, 1)] if interlace == 0 else [(0, 0, 8, 8), (4, 0, 8, 8), (0, 4, 4, 8), (2, 0, 4, 4), (0, 2, 2, 4), (1, 0, 2, 2), (0, 1, 1, 2)]
    position = 0
    for x, y, dx, dy in passes:
        width, height = (128 - x + dx - 1) // dx, (128 - y + dy - 1) // dy
        row_bytes = (width * channels * depth + 7) // 8
        for _ in range(height):
            if position + row_bytes + 1 > len(pixels) or pixels[position] > 4:
                raise MigrationError(f"Invalid PNG scanline or filter: {path}")
            position += row_bytes + 1
    if position != len(pixels):
        raise MigrationError(f"Incorrect PNG pixel data length: {path}")


@dataclass
class Change:
    path: Path
    before: str | None
    after: str | None
    staged: Path | None
    mode: int = 0o644
    owner: tuple[int, int] | None = None


@dataclass
class Plan:
    stage: Path
    changes: dict[Path, Change] = field(default_factory=dict)
    observations: dict[Path, str | None] = field(default_factory=dict)
    save_files: list[str] = field(default_factory=list)
    dimension_overrides: dict[str, str] = field(default_factory=dict)

    def observe(self, path: Path, data: bytes | None):
        actual = digest(data) if data is not None else None
        if path in self.observations and self.observations[path] != actual:
            raise MigrationError(f"File changed during preflight: {path}")
        self.observations[path] = actual

    def read(self, path: Path) -> bytes:
        data = read_regular(path)
        self.observe(path, data)
        return data

    def add(self, path: Path, data: bytes | None, owner: tuple[int, int] | None = None):
        no_symlink_parents(path)
        before_data = read_regular(path) if path.exists() else None
        before, after = digest(before_data) if before_data is not None else None, digest(data) if data is not None else None
        self.observe(path, before_data)
        if before == after:
            return
        if path in self.changes:
            if self.changes[path].after != after:
                raise MigrationError(f"Conflicting staged changes: {path}")
            return
        staged = self.stage / str(len(self.changes)) if data is not None else None
        if staged is not None:
            staged.write_bytes(data)
        info = path.stat() if path.exists() else None
        mode = info.st_mode & 0o777 if info is not None else 0o644
        owner = (info.st_uid, info.st_gid) if info is not None else owner
        self.changes[path] = Change(path, before, after, staged, mode, owner)

    def verify(self):
        for path, expected in self.observations.items():
            actual = digest(read_regular(path)) if path.exists() else None
            if actual != expected:
                raise MigrationError(f"File changed after preflight; migration aborted: {path}")


def prepare_images(source: Path, target: Path, index: dict[tuple[str, int], str], plan: Plan):
    source_maps = source / "maps"
    required = {path.split("/")[1] for path in index.values()}
    directories = []
    for directory in sorted(source_maps.iterdir()):
        if re.fullmatch(UUID_TEXT, directory.name):
            if directory.is_symlink() or not directory.is_dir():
                raise MigrationError(f"Invalid artwork directory: {directory}")
            directories.append(directory)
    found = {path.name for path in directories}
    if not required <= found:
        raise MigrationError(f"Missing artwork directories: {sorted(required - found)}")
    for directory in directories:
        tiles = set()
        for file in sorted(directory.iterdir()):
            data = plan.read(file)
            tile = TILE_NAME.fullmatch(file.name)
            if directory.name in required and (file.name == "thumb.png" or tile):
                validate_png(file, data)
            if tile:
                tiles.add((int(tile[1]), int(tile[2])))
            destination = target / "maps" / directory.name / file.name
            if destination.exists() and digest(plan.read(destination)) != digest(data):
                raise MigrationError(f"Target artwork file already exists with different content: {destination}")
            source_info = file.stat()
            plan.add(destination, data, (source_info.st_uid, source_info.st_gid))
        if directory.name in required:
            if not (directory / "thumb.png").is_file() or not tiles:
                raise MigrationError(f"Missing thumbnail or tiles: {directory}")
            width, height = max(x for x, _ in tiles) + 1, max(y for _, y in tiles) + 1
            if width * height != len(tiles):
                raise MigrationError(f"Incomplete rectangular tile matrix: {directory}")
    for relative in index.values():
        read_regular(source / relative)


def write_json(path: Path, value: dict):
    payload = json.dumps(value, indent=2, ensure_ascii=False).encode("utf-8") + b"\n"
    replace_bytes(path, payload, 0o600)


def fsync_directory(path: Path):
    descriptor = os.open(path, os.O_RDONLY | getattr(os, "O_DIRECTORY", 0))
    try:
        os.fsync(descriptor)
    finally:
        os.close(descriptor)


def set_owner(descriptor: int, owner: tuple[int, int]):
    current = os.fstat(descriptor)
    if (current.st_uid, current.st_gid) != owner:
        os.fchown(descriptor, *owner)


def mkdir_durable(path: Path, owner: tuple[int, int] | None = None):
    if path.is_dir():
        return
    mkdir_durable(path.parent, owner)
    path.mkdir(exist_ok=True)
    if owner is not None:
        descriptor = os.open(path, os.O_RDONLY | getattr(os, "O_DIRECTORY", 0))
        try:
            set_owner(descriptor, owner)
        finally:
            os.close(descriptor)
    fsync_directory(path.parent)


def replace_bytes(path: Path, data: bytes, mode: int, owner: tuple[int, int] | None = None):
    if owner is None and path.exists():
        info = path.stat()
        owner = (info.st_uid, info.st_gid)
    mkdir_durable(path.parent, owner)
    fd, temporary = tempfile.mkstemp(prefix=".recolorfulmap-", dir=path.parent)
    try:
        with os.fdopen(fd, "wb") as stream:
            stream.write(data)
            stream.flush()
            if owner is not None:
                set_owner(stream.fileno(), owner)
            os.fchmod(stream.fileno(), mode)
            os.fsync(stream.fileno())
        os.replace(temporary, path)
        fsync_directory(path.parent)
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)


def current_hash(path: Path) -> str | None:
    return digest(read_regular(path)) if path.exists() else None


def restore_transaction(directory: Path, manifest: dict, touched: list[dict] | None = None):
    changes = manifest["changes"] if touched is None else touched
    for change in changes:
        path = Path(change["path"])
        no_symlink_parents(path)
        current = current_hash(path)
        if current not in {change["before"], change["after"]}:
            raise MigrationError(f"Cannot rollback: file changed since migration: {path}")
        if change["before"] is not None:
            backup = directory / change["backup"]
            if digest(read_regular(backup)) != change["before"]:
                raise MigrationError(f"Backup checksum mismatch: {backup}")
    manifest["state"] = "rolling_back"
    write_json(directory / "manifest.json", manifest)
    for change in reversed(changes):
        path = Path(change["path"])
        if current_hash(path) == change["before"]:
            continue
        if change["before"] is None:
            path.unlink()
            fsync_directory(path.parent)
        else:
            replace_bytes(path, read_regular(directory / change["backup"]), change["mode"], tuple(change["owner"]) if change.get("owner") is not None else None)
    manifest["state"] = "rolled_back"
    write_json(directory / "manifest.json", manifest)


def commit(plan: Plan, root: Path, source: Path, target: Path, worlds: dict[str, Path], counts: Counts, unused: int) -> Path:
    plan.verify()
    base = root / TRANSACTION_DIR
    no_symlink_parents(base)
    mkdir_durable(base)
    directory = base / (datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ-") + uuid.uuid4().hex[:8])
    mkdir_durable(directory)
    mkdir_durable(directory / "backup")
    manifest = {"format": 1, "state": "preparing", "server_root": str(root), "source": str(source), "target": str(target),
                "worlds": {key: str(value) for key, value in worlds.items()}, "counts": vars(counts), "unused_index_entries": unused, "changes": [],
                "observed": {str(path): value for path, value in plan.observations.items()}, "save_files": plan.save_files,
                "dimension_overrides": plan.dimension_overrides,
                "source_indexes": {str(path): value for path, value in plan.observations.items() if path.parent == source / "maps" and path.name in {"maps.yml", "maps.yaml"}}}
    # Back up every before-image before any destination is changed.
    for i, change in enumerate(plan.changes.values()):
        backup = f"backup/{i}"
        if change.before is not None:
            data = read_regular(change.path)
            if digest(data) != change.before:
                raise MigrationError(f"File changed during backup: {change.path}")
            replace_bytes(directory / backup, data, 0o600)
        manifest["changes"].append({"path": str(change.path), "before": change.before, "after": change.after,
                                    "backup": backup if change.before is not None else None, "mode": change.mode, "owner": change.owner})
    manifest["state"] = "prepared"
    write_json(directory / "manifest.json", manifest)
    touched = []
    try:
        plan.verify()
        manifest["state"] = "committing"
        write_json(directory / "manifest.json", manifest)
        for change, record in zip(plan.changes.values(), manifest["changes"]):
            if current_hash(change.path) != change.before:
                raise MigrationError(f"File changed during commit: {change.path}")
            touched.append(record)
            if change.after is None:
                change.path.unlink()
                fsync_directory(change.path.parent)
            else:
                data = read_regular(change.staged)
                if digest(data) != change.after:
                    raise MigrationError(f"Staged data checksum mismatch: {change.staged}")
                replace_bytes(change.path, data, change.mode, change.owner)
        manifest["state"] = "complete"
        write_json(directory / "manifest.json", manifest)
    except BaseException as error:
        try:
            restore_transaction(directory, manifest, touched)
        except BaseException as rollback_error:
            raise MigrationError(f"Commit failed ({error}); automatic rollback also failed ({rollback_error}). Keep the server stopped and recover with --rollback {directory}") from rollback_error
        raise MigrationError(f"Commit failed; changed files were rolled back. Transaction: {directory}. Cause: {error}") from error
    return directory


def completed_transaction(root: Path, source: Path, target: Path) -> Path | None:
    base = root / TRANSACTION_DIR
    if not base.is_dir():
        return None
    for directory in sorted(base.iterdir(), reverse=True):
        file = directory / "manifest.json"
        if not file.is_file():
            continue
        manifest = json.loads(read_regular(file))
        if manifest.get("source") == str(source) and manifest.get("target") == str(target):
            if manifest.get("state") in {"prepared", "committing", "rolling_back"}:
                raise MigrationError(f"Interrupted migration exists; rollback before retry: --rollback {directory}")
            if manifest.get("state") == "complete":
                if source != target:
                    for path, expected in manifest.get("source_indexes", {}).items():
                        if current_hash(Path(path)) != expected:
                            raise MigrationError(f"Legacy source index changed after the completed migration: {path}; the old plugin must stay stopped. Previous transaction: {directory}")
                return directory
    return None


def all_save_files(worlds: dict[str, Path]) -> tuple[list[Path], list[Path], list[Path]]:
    dat_files, regions = stored_files(worlds)
    external = sorted({path for region in regions for path in region.parent.glob("c.*.*.mcc")})
    return dat_files, regions, external


def verify_completed(directory: Path, worlds: dict[str, Path], dimensions: dict[Path, str]):
    manifest = json.loads(read_regular(directory / "manifest.json"))
    if manifest["worlds"] != {key: str(value) for key, value in worlds.items()}:
        raise MigrationError(f"The world selection differs from the completed migration; no files changed. Previous transaction: {directory}")
    if manifest.get("dimension_overrides", {}) != {str(path): name for path, name in dimensions.items()}:
        raise MigrationError(f"The dimension names differ from the completed migration; no files changed. Previous transaction: {directory}")
    inputs = sorted(str(path) for group in all_save_files(worlds) for path in group)
    if inputs != manifest.get("save_files"):
        raise MigrationError(f"The save-file set changed after migration; do not rerun against a server that has been used. Previous transaction: {directory}")
    expected = dict(manifest.get("observed", {}))
    expected.update({change["path"]: change["after"] for change in manifest["changes"]})
    for path, checksum in expected.items():
        if current_hash(Path(path)) != checksum:
            raise MigrationError(f"Migration was completed, but this file subsequently changed: {path}. No files changed; keep using the migrated server or restore the full backup before retrying. Transaction: {directory}")


def run(args) -> dict:
    root = rooted(Path.cwd(), args.server_root)
    if args.rollback:
        directory = rooted(root, args.rollback)
        manifest = json.loads(read_regular(directory / "manifest.json"))
        if manifest.get("format") != 1 or manifest.get("server_root") != str(root):
            raise MigrationError("Rollback manifest does not match this server root or format")
        worlds = {key: Path(value) for key, value in manifest["worlds"].items()}
        with world_locks(worlds):
            restore_transaction(directory, manifest)
        return {"status": "rolled_back", "transaction": str(directory)}
    source, target = rooted(root, args.source), rooted(root, args.target)
    dimensions = {}
    for override in args.dimension:
        if "=" not in override:
            raise MigrationError("--dimension must be BUKKIT_NAME=PATH")
        name, value = override.split("=", 1)
        if not name or not value:
            raise MigrationError("--dimension must be BUKKIT_NAME=PATH")
        path = rooted(root, value)
        if path in dimensions or name in dimensions.values():
            raise MigrationError(f"Duplicate --dimension name or path: {override}")
        dimensions[path] = name
    previous = completed_transaction(root, source, target)
    if previous is not None:
        worlds = discover_worlds(root, args.world)
        with world_locks(worlds):
            verify_completed(previous, worlds, dimensions)
        return {"status": "already_migrated", "transaction": str(previous),
                "message": "This source/target migration is already complete; no files were changed."}
    index_file, index, index_bytes = load_index(source)
    worlds = discover_worlds(root, args.world)
    with world_locks(worlds), tempfile.TemporaryDirectory(prefix="recolorfulmap-migration-") as temporary:
        plan = Plan(Path(temporary))
        plan.dimension_overrides = {str(path): name for path, name in dimensions.items()}
        plan.observe(index_file, index_bytes)
        dat_files, regions, external_files = all_save_files(worlds)
        plan.save_files = sorted(str(path) for group in (dat_files, regions, external_files) for path in group)
        resolver = Resolver(index, worlds, dat_files, plan, dimensions)
        counts = Counts()
        prepare_images(source, target, index, plan)
        for path in dat_files:
            data = plan.read(path)
            document, codec = decode_dat(data)
            if migrate_document(document, resolver, counts):
                plan.add(path, encode_dat(document, codec))
        for external in external_files:
            plan.read(external)
        for path in regions:
            plan.read(path)

            def transform(raw: bytes, context: str) -> bytes | None:
                try:
                    document = parse(raw)
                    return dump(document) if migrate_document(document, resolver, counts) else None
                except (NBTError, MigrationError) as error:
                    raise MigrationError(f"{context}: {error}") from error

            for changed_path, data in rewrite_region(path, transform).items():
                plan.add(changed_path, data)
        # The target must start without any old index. In-place migration backs up
        # and removes its own index, while a distinct source remains untouched.
        for name in ("maps.yml", "maps.yaml"):
            old_target = target / "maps" / name
            if old_target.exists():
                if source != target and plan.read(old_target) != index_bytes:
                    raise MigrationError(f"Target has a different legacy index: {old_target}")
                plan.add(old_target, None)
        unused = len(index.keys() - resolver.used)
        result = {"status": "applied" if args.apply else "dry_run", "worlds": list(worlds), **vars(counts),
                  "unused_index_entries": unused, "changed_files": len(plan.changes), "source": str(source), "target": str(target)}
        if args.apply:
            result["transaction"] = str(commit(plan, root, source, target, worlds, counts, unused))
        return result


def parser() -> argparse.ArgumentParser:
    result = argparse.ArgumentParser(description=__doc__)
    result.add_argument("--server-root", default=".", help="Server directory containing plugins and worlds")
    result.add_argument("--source", default="plugins/ColorfulMap", help="Legacy plugin directory, relative to server root")
    result.add_argument("--target", default="plugins/ReColorfulMap", help="New plugin directory, relative to server root")
    result.add_argument("--world", action="append", default=[], metavar="NAME=PATH", help="Explicit Bukkit world name and location (repeatable)")
    result.add_argument("--dimension", action="append", default=[], metavar="BUKKIT_NAME=PATH", help="Explicit Bukkit name for a custom Paper 26.2 dimension directory (repeatable)")
    result.add_argument("--apply", action="store_true", help="Commit after all preflight checks pass; default only reports a dry run")
    result.add_argument("--rollback", metavar="TRANSACTION", help="Restore a previous migration after checking backups and current-file hashes")
    return result


def main() -> int:
    try:
        result = run(parser().parse_args())
        print(json.dumps(result, indent=2, ensure_ascii=False))
        return 0
    except (MigrationError, NBTError, RegionError, OSError, yaml.YAMLError, UnicodeError, zlib.error, ValueError) as error:
        print(f"Migration stopped without a partial successful result: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
