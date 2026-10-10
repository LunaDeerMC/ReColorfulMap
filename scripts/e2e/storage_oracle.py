#!/usr/bin/env python3
"""Independent, read-only oracle for an offline ColorfulMap migration fixture.

This deliberately does not import the migration, its NBT codec, or its region
codec. Its only non-standard dependency is PyYAML for the legacy fixture index.
Use immutable snapshots of a stopped server for both inputs.
"""

from __future__ import annotations

import argparse
from collections import Counter
from dataclasses import dataclass
import gzip
import hashlib
import json
from pathlib import Path, PurePosixPath
import re
import struct
import sys
import zlib

import yaml


PDC = b"re_colorful_map:image_path"
MAX_BYTES = 256 * 1024 * 1024
UUID_DIRECTORY = re.compile(r"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\Z")
EXCLUDED = {"plugins", "backups", "backup", "logs", "libraries", "versions", "cache", ".git", ".recolorfulmap-migrations"}


class OracleError(ValueError):
    pass


def require(condition, message):
    if not condition:
        raise OracleError(message)


@dataclass
class Node:
    kind: int
    value: object


class Reader:
    """Independent wire reader: primitive payloads and field names stay bytes."""

    def __init__(self, raw):
        require(len(raw) <= MAX_BYTES, "NBT input exceeds oracle size limit")
        self.raw = raw
        self.offset = 0

    def take(self, length):
        require(length >= 0 and self.offset + length <= len(self.raw), "Truncated NBT")
        result = self.raw[self.offset:self.offset + length]
        self.offset += length
        return result

    def name(self):
        return self.take(struct.unpack(">H", self.take(2))[0])

    def count(self):
        count = struct.unpack(">i", self.take(4))[0]
        require(0 <= count <= MAX_BYTES, "Invalid NBT collection count")
        return count

    def node(self, kind, depth=0):
        require(depth <= 512, "NBT exceeds oracle depth limit")
        require(1 <= kind <= 12, f"Invalid NBT kind {kind}")
        start = self.offset
        if kind <= 6:
            self.take({1: 1, 2: 2, 3: 4, 4: 8, 5: 4, 6: 8}[kind])
        elif kind in (7, 11, 12):
            self.take(self.count() * {7: 1, 11: 4, 12: 8}[kind])
        elif kind == 8:
            self.name()
        elif kind == 9:
            element = self.take(1)[0]
            count = self.count()
            require(0 <= element <= 12 and (element != 0 or count == 0), "Invalid NBT list type")
            return Node(kind, (element, [self.node(element, depth + 1) for _ in range(count)]))
        elif kind == 10:
            entries = []
            while True:
                child_kind = self.take(1)[0]
                if child_kind == 0:
                    return Node(kind, entries)
                name = self.name()
                entries.append((name, self.node(child_kind, depth + 1)))
        return Node(kind, self.raw[start:self.offset])

    def document(self):
        require(self.take(1) == b"\x0a", "NBT root must be compound")
        name = self.name()
        root = self.node(10)
        require(self.offset == len(self.raw), "Trailing bytes in NBT")
        return name, root


def read_nbt(raw):
    return Reader(raw).document()


def read_dat(raw):
    if raw.startswith(b"\x1f\x8b"):
        return "gzip", read_nbt(gzip.decompress(raw))
    if len(raw) > 1 and raw[0] == 0x78 and int.from_bytes(raw[:2], "big") % 31 == 0:
        return "zlib", read_nbt(zlib.decompress(raw))
    return "raw", read_nbt(raw)


def field(node, name):
    require(node is not None and node.kind == 10, f"Expected compound for {name!r}")
    found = [value for key, value in node.value if key == name]
    require(len(found) <= 1, f"Ambiguous duplicate NBT field {name!r}")
    return found[0] if found else None


def text_value(node):
    require(node is not None and node.kind == 8, "Expected NBT string")
    return node.value[2:].decode("utf-8", "strict")


def number_value(node):
    formats = {1: ">b", 2: ">h", 3: ">i", 4: ">q"}
    require(node is not None and node.kind in formats, "Expected NBT integer")
    return struct.unpack(formats[node.kind], node.value)[0]


def delete_field(node, name):
    require(field(node, name) is not None, f"Cannot remove missing field {name!r}")
    node.value = [(key, value) for key, value in node.value if key != name]


def path_component(raw):
    try:
        return raw.decode("utf-8", "strict")
    except UnicodeDecodeError:
        return "<raw:" + raw.hex() + ">"


def map_identity(node):
    if node.kind != 10:
        return None
    identifier = field(node, b"id")
    # Compare wire bytes to avoid decoding unrelated Java Modified UTF-8 IDs.
    if identifier is None or identifier.kind != 8 or identifier.value[2:] != b"minecraft:filled_map":
        return None
    components = field(node, b"components")
    base_key = b"components" if components is not None else b"tag"
    base = field(node, base_key)
    if base is None:
        return None
    identifier = field(base, b"minecraft:map_id" if components is not None else b"map")
    if identifier is None:
        return None
    return number_value(identifier), base_key


class Comparison:
    def __init__(self, index):
        self.index = index
        self.added = 0
        self.maps = []
        self.vanilla_maps = 0
        self.locations = Counter()

    def normalize_map(self, before, after, identity, location, node_path):
        identifier, base_key = identity
        expected = self.index.get(identifier)
        if expected is None:
            self.vanilla_maps += 1
            return
        old_base = field(before, base_key)
        new_base = field(after, base_key)
        require(new_base is not None, f"{location}{node_path}: map container removed")
        modern = base_key == b"components"
        old_custom = field(old_base, b"minecraft:custom_data") if modern else old_base
        new_custom = field(new_base, b"minecraft:custom_data") if modern else new_base
        require(new_custom is not None, f"{location}{node_path}: missing migrated custom data")
        old_pdc = field(old_custom, b"PublicBukkitValues") if old_custom is not None else None
        new_pdc = field(new_custom, b"PublicBukkitValues")
        require(new_pdc is not None, f"{location}{node_path}: missing migrated PDC")
        old_reference = field(old_pdc, PDC) if old_pdc is not None else None
        new_reference = field(new_pdc, PDC)
        require(new_reference is not None and text_value(new_reference) == expected,
                f"{location}{node_path}: map {identifier} does not reference {expected!r}")
        if old_reference is not None:
            require(text_value(old_reference) == expected, f"{location}{node_path}: before fixture has a conflicting PDC")
        else:
            # Remove exactly the permitted new field. A parent may disappear
            # only when it was absent before and is now entirely empty.
            delete_field(new_pdc, PDC)
            if old_pdc is None:
                require(not new_pdc.value, f"{location}{node_path}: extra fields appeared in new PDC")
                delete_field(new_custom, b"PublicBukkitValues")
            if modern and old_custom is None:
                require(not new_custom.value, f"{location}{node_path}: extra fields appeared in new custom data")
                delete_field(new_base, b"minecraft:custom_data")
            self.added += 1
        self.locations[location] += 1
        self.maps.append({"location": location, "nbt_path": node_path, "map_id": identifier,
                          "image_path": expected, "added": old_reference is None})

    def nodes(self, before, after, location, node_path="$", depth=0):
        require(depth <= 512, "Comparison nesting limit reached")
        require(before.kind == after.kind, f"{location}{node_path}: NBT type changed")
        identity = map_identity(before)
        if identity is not None:
            self.normalize_map(before, after, identity, location, node_path)
        if before.kind == 10:
            old_names = [name for name, _ in before.value]
            new_names = [name for name, _ in after.value]
            require(old_names == new_names, f"{location}{node_path}: compound names/order changed")
            for (name, old), (_, new) in zip(before.value, after.value):
                self.nodes(old, new, location, node_path + "/" + path_component(name), depth + 1)
        elif before.kind == 9:
            old_kind, old_children = before.value
            new_kind, new_children = after.value
            require(old_kind == new_kind and len(old_children) == len(new_children),
                    f"{location}{node_path}: list element type/count changed")
            for i, (old, new) in enumerate(zip(old_children, new_children)):
                self.nodes(old, new, location, node_path + f"[{i}]", depth + 1)
        else:
            require(before.value == after.value, f"{location}{node_path}: primitive wire bytes changed")

    def documents(self, before, after, location):
        require(before[0] == after[0], f"{location}: NBT root name changed")
        added_before = self.added
        self.nodes(before[1], after[1], location)
        return self.added - added_before


@dataclass
class Chunk:
    codec: int
    external: Path | None
    record: bytes
    raw: bytes


def read_region(path):
    data = path.read_bytes()
    require(len(data) >= 8192 and len(data) % 4096 == 0, f"Invalid region size: {path}")
    match = re.fullmatch(r"r\.(-?\d+)\.(-?\d+)\.mca", path.name)
    require(match is not None, f"Unexpected region name: {path}")
    region_x, region_z = map(int, match.groups())
    chunks = {}
    for slot in range(1024):
        location = struct.unpack_from(">I", data, slot * 4)[0]
        if location == 0:
            continue
        sector, sectors = location >> 8, location & 255
        require(sector >= 2 and sectors > 0 and (sector + sectors) * 4096 <= len(data),
                f"Invalid region location: {path} slot {slot}")
        record = data[sector * 4096:(sector + sectors) * 4096]
        length = struct.unpack_from(">I", record)[0]
        require(1 <= length <= len(record) - 4, f"Invalid chunk length: {path} slot {slot}")
        kind = record[4]
        codec = kind & 127
        require(codec in (1, 2, 3), f"Unsupported oracle region compression {codec}: {path}")
        external = None
        if kind & 128:
            require(length == 1, f"Unexpected external region stub: {path}")
            external = path.parent / f"c.{region_x * 32 + slot % 32}.{region_z * 32 + slot // 32}.mcc"
            payload = external.read_bytes()
        else:
            payload = record[5:4 + length]
        raw = gzip.decompress(payload) if codec == 1 else zlib.decompress(payload) if codec == 2 else payload
        chunks[slot] = Chunk(codec, external, record, raw)
    return data[4096:8192], chunks


def file_hash(path):
    hasher = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            hasher.update(block)
    return hasher.hexdigest()


def files_below(path):
    require(path.is_dir() and not path.is_symlink(), f"Missing or symbolic-link directory: {path}")
    result = {}
    for entry in path.rglob("*"):
        require(not entry.is_symlink(), f"Oracle refuses symbolic links: {entry}")
        if entry.is_file():
            result[entry.relative_to(path).as_posix()] = entry
    return result


def world_directories(root):
    import os
    result = {}
    for directory, names, filenames in os.walk(root, followlinks=False):
        names[:] = sorted(name for name in names if name not in EXCLUDED and not name.startswith("."))
        if "level.dat" in filenames:
            world = Path(directory)
            result[world.relative_to(root).as_posix()] = world
            names.clear()
    require(result, f"No world level.dat files found below {root}")
    return result


class IndexLoader(yaml.SafeLoader):
    pass


def unique_yaml_mapping(loader, node, deep=False):
    result = {}
    for key_node, value_node in node.value:
        key = loader.construct_object(key_node, deep=deep)
        require(key not in result, f"Duplicate legacy YAML key: {key!r}")
        result[key] = loader.construct_object(value_node, deep=deep)
    return result


IndexLoader.add_constructor(yaml.resolver.BaseResolver.DEFAULT_MAPPING_TAG, unique_yaml_mapping)


def load_index(source):
    candidates = [source / "maps" / name for name in ("maps.yml", "maps.yaml") if (source / "maps" / name).is_file()]
    require(len(candidates) == 1, f"Expected one legacy maps.yml/maps.yaml under {source}")
    value = yaml.load(candidates[0].read_text(encoding="utf-8"), Loader=IndexLoader)
    require(isinstance(value, dict), "Legacy index must be a mapping")
    index = {}
    for world, entries in value.items():
        require(isinstance(world, str) and isinstance(entries, dict), "Invalid legacy world mapping")
        for identifier, image_path in entries.items():
            require(not isinstance(identifier, bool) and re.fullmatch(r"0|[1-9]\d*", str(identifier)), "Invalid legacy map ID")
            identifier = int(identifier)
            require(identifier not in index, f"Oracle fixture requires globally unique legacy map IDs; duplicate {identifier}")
            require(isinstance(image_path, str), "Legacy image path must be a string")
            path = PurePosixPath(image_path)
            require(not path.is_absolute() and len(path.parts) == 3 and path.parts[0] == "maps"
                    and UUID_DIRECTORY.fullmatch(path.parts[1]) and ".." not in path.parts,
                    f"Unexpected legacy image path: {image_path!r}")
            require((source / image_path).is_file(), f"Missing source image: {image_path}")
            index[identifier] = image_path
    return index


def relative_plugin(root, value):
    path = PurePosixPath(value)
    require(not path.is_absolute() and ".." not in path.parts, "Plugin paths must be relative to the server root")
    return root.joinpath(*path.parts)


def compare_images(before, after, source_name, target_name):
    source = relative_plugin(before, source_name)
    target = relative_plugin(after, target_name)
    original = files_below(source / "maps")
    copied = {name: path for name, path in original.items()
              if UUID_DIRECTORY.fullmatch(PurePosixPath(name).parts[0])}
    old_target = relative_plugin(before, target_name) / "maps"
    expected = {name: path for name, path in files_below(old_target).items()
                if name not in {"maps.yml", "maps.yaml"}} if old_target.is_dir() else {}
    for name, path in copied.items():
        require(name not in expected or file_hash(expected[name]) == file_hash(path),
                f"Conflicting before-fixture target image: {name}")
        expected[name] = path
    actual = files_below(target / "maps")
    require("maps.yml" not in actual and "maps.yaml" not in actual, "Target still has a legacy map index")
    require(set(expected) == set(actual), "Target artwork file set differs from the source UUID directories")
    for name, path in expected.items():
        require(file_hash(path) == file_hash(actual[name]), f"Image/metadata copy differs: {name}")
    if source_name != target_name:
        retained = files_below(relative_plugin(after, source_name))
        old_source = files_below(source)
        require(set(retained) == set(old_source), "Migration changed the retained legacy plugin file set")
        for name, path in old_source.items():
            require(file_hash(path) == file_hash(retained[name]), f"Retained legacy source changed: {name}")
    return len(copied)


def verify(before_root, after_root, source_name="plugins/ColorfulMap", target_name="plugins/ReColorfulMap"):
    before_root, after_root = Path(before_root).resolve(), Path(after_root).resolve()
    require(before_root != after_root, "Before and after must be separate snapshots")
    index = load_index(relative_plugin(before_root, source_name))
    comparison = Comparison(index)
    before_worlds, after_worlds = world_directories(before_root), world_directories(after_root)
    require(set(before_worlds) == set(after_worlds), "World directory set changed")
    totals = Counter()
    changed_files = []
    for name, world in sorted(before_worlds.items()):
        old_files, new_files = files_below(world), files_below(after_worlds[name])
        require(set(old_files) == set(new_files), f"{name}: world file set changed")
        hashes_before = {key: file_hash(value) for key, value in old_files.items()}
        hashes_after = {key: file_hash(value) for key, value in new_files.items()}
        totals["world_files"] += len(old_files)
        totals["unchanged_files"] += sum(hashes_before[key] == hashes_after[key] for key in old_files)
        handled = set()
        for key in sorted(old_files):
            relative = PurePosixPath(key)
            label = name + "/" + key
            if relative.suffix == ".mca" and relative.parent.name in {"region", "entities"}:
                timestamps, old_chunks = read_region(old_files[key])
                new_timestamps, new_chunks = read_region(new_files[key])
                require(timestamps == new_timestamps, f"{label}: region timestamps changed")
                require(set(old_chunks) == set(new_chunks), f"{label}: chunk slots changed")
                region_additions = 0
                for slot, old_chunk in old_chunks.items():
                    new_chunk = new_chunks[slot]
                    context = f"{label}#slot={slot}"
                    require(old_chunk.codec == new_chunk.codec and (old_chunk.external is None) == (new_chunk.external is None),
                            f"{context}: chunk compression/storage changed")
                    added = comparison.documents(read_nbt(old_chunk.raw), read_nbt(new_chunk.raw), context)
                    region_additions += added
                    if not added:
                        require(old_chunk.raw == new_chunk.raw and old_chunk.record == new_chunk.record,
                                f"{context}: unrelated chunk bytes changed")
                    if old_chunk.external is not None:
                        old_external = old_chunk.external.relative_to(world).as_posix()
                        new_external = new_chunk.external.relative_to(after_worlds[name]).as_posix()
                        require(old_external == new_external, f"{context}: external chunk address changed")
                        require(old_chunk.record == new_chunk.record, f"{context}: external stub changed")
                        if not added:
                            require(hashes_before[old_external] == hashes_after[new_external], f"{context}: unrelated MCC bytes changed")
                        handled.add(old_external)
                        totals["external_chunks"] += 1
                    totals["chunks"] += 1
                if not region_additions:
                    require(hashes_before[key] == hashes_after[key], f"{label}: unrelated MCA bytes changed")
                handled.add(key)
                totals["region_files"] += 1
            elif relative.name == "level.dat" or (relative.suffix == ".dat" and {"data", "playerdata"}.intersection(relative.parts[:-1])):
                old_codec, old_doc = read_dat(old_files[key].read_bytes())
                new_codec, new_doc = read_dat(new_files[key].read_bytes())
                require(old_codec == new_codec, f"{label}: DAT compression changed")
                added = comparison.documents(old_doc, new_doc, label)
                if not added:
                    require(hashes_before[key] == hashes_after[key], f"{label}: unrelated DAT bytes changed")
                handled.add(key)
                totals["nbt_files"] += 1
        for key in old_files:
            if key not in handled:
                require(hashes_before[key] == hashes_after[key], f"{name}/{key}: unrelated world file bytes changed")
            if hashes_before[key] != hashes_after[key]:
                changed_files.append(name + "/" + key)
    copied_files = compare_images(before_root, after_root, source_name, target_name)
    return {"status": "verified", "before": str(before_root), "after": str(after_root),
            "worlds": sorted(before_worlds), "legacy_index_entries": len(index),
            "indexed_map_items": len(comparison.maps), "added_path_fields": comparison.added,
            "vanilla_map_items": comparison.vanilla_maps, "copied_artwork_files": copied_files,
            **totals, "changed_files": sorted(changed_files),
            "map_items_by_location": dict(sorted(comparison.locations.items())), "map_items": comparison.maps}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--before", required=True, type=Path, help="Immutable stopped-server snapshot before migration")
    parser.add_argument("--after", required=True, type=Path, help="Stopped-server snapshot after migration")
    parser.add_argument("--source", default="plugins/ColorfulMap")
    parser.add_argument("--target", default="plugins/ReColorfulMap")
    args = parser.parse_args()
    try:
        report = verify(args.before, args.after, args.source, args.target)
    except (OracleError, OSError, ValueError, KeyError, struct.error, zlib.error, yaml.YAMLError) as error:
        print(json.dumps({"status": "failed", "error": str(error)}, ensure_ascii=False), file=sys.stderr)
        return 1
    print(json.dumps(report, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    sys.exit(main())
