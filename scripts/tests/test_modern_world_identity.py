"""Paper 26.2 identity regressions using synthetic namespaced SavedData."""

from __future__ import annotations

import gzip
import json
from pathlib import Path
import struct
import sys
import tempfile
import unittest
import uuid
import zlib

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import migrate_colorfulmap as migration
from nbt_codec import Document, Tag, compound, dump, integer, string, string_value


OVERWORLD_UUID = uuid.UUID("aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee")
NETHER_UUID = uuid.UUID("bbbbbbbb-cccc-4ddd-8eee-ffffffffffff")
END_UUID = uuid.UUID("cccccccc-dddd-4eee-8fff-000000000000")
CUSTOM_UUID = uuid.UUID("dddddddd-eeee-4fff-8000-111111111111")
UNKNOWN_UUID = uuid.UUID("eeeeeeee-ffff-4000-8111-222222222222")
ART = "12345678-1234-4234-8234-123456789abc"
OTHER_ART = "87654321-4321-4321-8321-cba987654321"
REFERENCE = f"maps/{ART}/thumb.png"
OTHER_REFERENCE = f"maps/{OTHER_ART}/thumb.png"


def write_dat(path, root):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(gzip.compress(dump(Document(b"", root)), mtime=0))
    return path


def png():
    def chunk(kind, payload):
        return (struct.pack(">I", len(payload)) + kind + payload
                + struct.pack(">I", zlib.crc32(kind + payload) & 0xffffffff))

    return (b"\x89PNG\r\n\x1a\n"
            + chunk(b"IHDR", struct.pack(">IIBBBBB", 128, 128, 8, 6, 0, 0, 0))
            + chunk(b"IDAT", zlib.compress((b"\0" + b"\x20\x40\x80\xff" * 128) * 128))
            + chunk(b"IEND", b""))


class ModernWorldIdentityTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="modern-world-identity-")
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.world = self.make_world("world")
        self.worlds = {"world": self.world}

    def make_world(self, name):
        directory = self.root / name
        directory.mkdir()
        write_dat(directory / "level.dat", compound([("Data", compound())]))
        (directory / "session.lock").write_bytes(b"\xe2\x98\x83")
        return directory

    def metadata(self, key, identity, *, world=None, extra=None):
        namespace, location = key.split(":", 1)
        directory = (world or self.world) / "dimensions" / namespace / location
        entries = [("uuid", Tag(11, struct.pack(">i", 4) + identity.bytes))]
        if extra is not None:
            entries.append(("sentinel", integer(extra)))
        path = write_dat(directory / "data" / "paper" / "metadata.dat",
                         compound([("data", compound(entries)), ("DataVersion", integer(4903))]))
        return directory, path

    def native(self, identifier, identity, *, dimension="minecraft:overworld", world=None):
        entries = [("dimension", string(dimension))]
        if identity is not None:
            most, least = struct.unpack(">qq", identity.bytes)
            entries += [("UUIDMost", Tag(4, struct.pack(">q", most))),
                        ("UUIDLeast", Tag(4, struct.pack(">q", least)))]
        return write_dat((world or self.world) / "data" / "minecraft" / "maps" / f"{identifier}.dat",
                         compound([("data", compound(entries))]))

    def resolver(self, index, *, overrides=None, plan=None):
        files, _ = migration.stored_files(self.worlds)
        return migration.Resolver(index, self.worlds, files, plan=plan, dimension_overrides=overrides)

    def snapshot(self):
        return {str(path.relative_to(self.root)): path.read_bytes()
                for path in self.root.rglob("*") if path.is_file()}

    def test_real_26_2_int_array_uuid_shape_without_root_uid_dat(self):
        _, metadata = self.metadata("minecraft:overworld", OVERWORLD_UUID)
        native = self.native(47, OVERWORLD_UUID)
        self.assertFalse((self.world / "uid.dat").exists())
        stored, _ = migration.stored_files(self.worlds)
        self.assertIn(metadata, stored)
        self.assertIn(native, stored)
        resolver = self.resolver({("world", 47): REFERENCE})
        self.assertEqual(resolver.resolve(47), REFERENCE)
        self.assertEqual(resolver.used, {("world", 47)})

    def test_same_map_id_across_default_dimension_indices_uses_native_uuid(self):
        dimensions = [
            ("minecraft:overworld", "world", OVERWORLD_UUID, REFERENCE),
            ("minecraft:the_nether", "world_nether", NETHER_UUID, OTHER_REFERENCE),
            ("minecraft:the_end", "world_the_end", END_UUID, f"maps/{ART}/0_0.png"),
        ]
        index = {(name, 47): reference for _, name, _, reference in dimensions}
        for key, _, identity, _ in dimensions:
            self.metadata(key, identity)
        for key, name, identity, reference in dimensions:
            with self.subTest(dimension=key):
                # The file and its carrier remain in the root world; only the
                # native map UUID determines which old index section applies.
                self.native(47, identity, dimension=key)
                resolver = self.resolver(index)
                self.assertEqual(resolver.resolve(47), reference)
                self.assertEqual(resolver.used, {(name, 47)})

    def test_default_dimension_names_use_explicit_root_alias(self):
        self.metadata("minecraft:the_nether", NETHER_UUID)
        self.native(47, NETHER_UUID, dimension="minecraft:the_nether")
        self.worlds = {"Lobby MiXeD Name": self.world}
        resolver = self.resolver({("Lobby MiXeD Name_nether", 47): REFERENCE})
        self.assertEqual(resolver.resolve(47), REFERENCE)

    def test_unmatched_native_uuid_refuses_even_if_dimension_name_matches(self):
        self.metadata("minecraft:overworld", OVERWORLD_UUID)
        self.native(47, UNKNOWN_UUID)
        resolver = self.resolver({("world", 47): REFERENCE})
        with self.assertRaisesRegex(migration.MigrationError, "source world cannot be identified"):
            resolver.resolve(47)

    def test_without_native_uuid_unique_default_dimension_metadata_can_resolve(self):
        self.metadata("minecraft:overworld", OVERWORLD_UUID)
        self.metadata("minecraft:the_nether", NETHER_UUID)
        self.native(47, None, dimension="minecraft:the_nether")
        resolver = self.resolver({("world", 47): REFERENCE, ("world_nether", 47): OTHER_REFERENCE})
        self.assertEqual(resolver.resolve(47), OTHER_REFERENCE)

    def test_custom_creator_name_is_not_guessed_from_dimension_folder(self):
        self.metadata("example:build/arena", CUSTOM_UUID)
        self.native(47, CUSTOM_UUID, dimension="example:build/arena")
        resolver = self.resolver({("world_example_build/arena", 47): REFERENCE})
        with self.assertRaisesRegex(migration.MigrationError, "--dimension"):
            resolver.resolve(47)

    def test_custom_dimension_without_uuid_still_requires_name_override(self):
        self.metadata("example:build/arena", CUSTOM_UUID)
        self.native(47, None, dimension="example:build/arena")
        resolver = self.resolver({("Custom World", 47): REFERENCE})
        with self.assertRaises(migration.MigrationError):
            resolver.resolve(47)

    def test_custom_dimension_override_preserves_exact_names_and_nested_path(self):
        directory, _ = self.metadata("example:build/arena", CUSTOM_UUID)
        self.native(47, CUSTOM_UUID, dimension="example:build/arena")
        for name in ("Creative World", "MiXeD_case Name", "建筑 Worlds 空格"):
            with self.subTest(name=name):
                resolver = self.resolver({(name, 47): REFERENCE}, overrides={directory: name})
                self.assertEqual(resolver.resolve(47), REFERENCE)
                self.assertEqual(resolver.used, {(name, 47)})

    def test_custom_override_can_resolve_uuidless_native_map(self):
        directory, _ = self.metadata("example:build/arena", CUSTOM_UUID)
        self.native(47, None, dimension="example:build/arena")
        resolver = self.resolver({("Build Arena", 47): REFERENCE}, overrides={directory: "Build Arena"})
        self.assertEqual(resolver.resolve(47), REFERENCE)

    def test_custom_override_cannot_steal_another_dimensions_bukkit_name(self):
        self.metadata("minecraft:overworld", OVERWORLD_UUID)
        directory, _ = self.metadata("example:realm", CUSTOM_UUID)
        self.native(47, CUSTOM_UUID, dimension="example:realm")
        with self.assertRaisesRegex(migration.MigrationError, "Conflicting Bukkit UUIDs"):
            self.resolver({("world", 47): REFERENCE}, overrides={directory: "world"})

    def test_uuidless_map_cannot_select_one_named_dimension_while_another_is_unnamed(self):
        second = self.make_world("second")
        self.worlds["second"] = second
        named, _ = self.metadata("example:realm", CUSTOM_UUID)
        self.metadata("example:realm", UNKNOWN_UUID, world=second)
        self.native(47, None, dimension="example:realm", world=second)
        resolver = self.resolver({("Known Realm", 47): REFERENCE}, overrides={named: "Known Realm"})
        with self.assertRaisesRegex(migration.MigrationError, "cannot be identified"):
            resolver.resolve(47)

    def test_override_for_nonexistent_dimension_is_rejected(self):
        self.metadata("minecraft:overworld", OVERWORLD_UUID)
        with self.assertRaisesRegex(migration.MigrationError, "does not identify discovered Paper metadata"):
            self.resolver({}, overrides={self.world / "dimensions/example/missing": "Missing World"})

    def test_multiple_world_roots_cannot_share_metadata_uuid(self):
        second = self.make_world("second")
        self.worlds["second"] = second
        self.metadata("minecraft:overworld", OVERWORLD_UUID)
        self.metadata("minecraft:overworld", OVERWORLD_UUID, world=second)
        with self.assertRaisesRegex(migration.MigrationError, "Duplicate Bukkit world UUID"):
            self.resolver({})

    def test_two_dimensions_cannot_share_metadata_uuid(self):
        self.metadata("minecraft:overworld", OVERWORLD_UUID)
        self.metadata("minecraft:the_nether", OVERWORLD_UUID)
        with self.assertRaisesRegex(migration.MigrationError, "Duplicate Bukkit world UUID"):
            self.resolver({})

    def test_legacy_uid_and_matching_overworld_metadata_are_accepted(self):
        (self.world / "uid.dat").write_bytes(OVERWORLD_UUID.bytes)
        self.metadata("minecraft:overworld", OVERWORLD_UUID)
        self.native(47, OVERWORLD_UUID)
        self.assertEqual(self.resolver({("world", 47): REFERENCE}).resolve(47), REFERENCE)

    def test_legacy_uid_and_conflicting_overworld_metadata_are_rejected(self):
        (self.world / "uid.dat").write_bytes(OVERWORLD_UUID.bytes)
        self.metadata("minecraft:overworld", NETHER_UUID)
        with self.assertRaises(migration.MigrationError):
            self.resolver({})

    def test_plan_observes_metadata_and_rejects_changes_after_resolution(self):
        _, metadata = self.metadata("minecraft:overworld", OVERWORLD_UUID)
        self.native(47, OVERWORLD_UUID)
        plan = migration.Plan(self.root / "stage")
        resolver = self.resolver({("world", 47): REFERENCE}, plan=plan)
        self.assertEqual(resolver.resolve(47), REFERENCE)
        self.assertIn(metadata, plan.observations)
        observed = plan.observations[metadata]
        self.metadata("minecraft:overworld", OVERWORLD_UUID, extra=99)
        self.assertNotEqual(migration.digest(metadata.read_bytes()), observed)
        with self.assertRaisesRegex(migration.MigrationError, "changed after preflight"):
            plan.verify()

    def test_malformed_metadata_uuid_never_falls_back_to_dimension_name(self):
        _, path = self.metadata("minecraft:overworld", OVERWORLD_UUID)
        invalid = [Tag(11, struct.pack(">i", 3) + OVERWORLD_UUID.bytes[:12]),
                   string(str(OVERWORLD_UUID)), Tag(7, struct.pack(">i", 16) + OVERWORLD_UUID.bytes)]
        for identity in invalid:
            with self.subTest(kind=identity.kind):
                write_dat(path, compound([("data", compound([("uuid", identity)]))]))
                with self.assertRaisesRegex(migration.MigrationError, "UUID int-array"):
                    self.resolver({})

    def test_cli_custom_dimension_dry_run_apply_repeat_and_rollback(self):
        directory, metadata = self.metadata("example:build/arena", CUSTOM_UUID)
        native = self.native(47, CUSTOM_UUID, dimension="example:build/arena")
        metadata_before, native_before = metadata.read_bytes(), native.read_bytes()
        name = "MiXeD Build World 空格"
        source = self.root / "plugins" / "ColorfulMap"
        images = source / "maps" / ART
        images.mkdir(parents=True)
        for filename in ("thumb.png", "0_0.png"):
            (images / filename).write_bytes(png())
        (images.parent / "maps.yml").write_text(migration.yaml.safe_dump({name: {47: REFERENCE}}), encoding="utf-8")
        item = compound([("id", string("minecraft:filled_map")), ("count", integer(1)),
                         ("components", compound([("minecraft:map_id", integer(47))]))])
        player = write_dat(self.world / "players" / "data" / "player.dat",
                           compound([("Inventory", Tag(9, (10, [item])))]))
        common = ["--server-root", str(self.root)]
        before = self.snapshot()
        with self.assertRaisesRegex(migration.MigrationError, "--dimension"):
            migration.run(migration.parser().parse_args(common))
        self.assertEqual(self.snapshot(), before)
        flag = f"{name}={directory.relative_to(self.root).as_posix()}"
        args = common + ["--dimension", flag]
        self.assertEqual(migration.run(migration.parser().parse_args(args))["migrated"], 1)
        self.assertEqual(self.snapshot(), before)
        applied = migration.run(migration.parser().parse_args(args + ["--apply"]))
        self.assertEqual(applied["status"], "applied")
        migrated = migration.decode_dat(player.read_bytes())[0].root.get("Inventory").value[1][0]
        path = migrated.get("components").get("minecraft:custom_data").get("PublicBukkitValues").get(migration.PDC_KEY)
        self.assertEqual(string_value(path), REFERENCE)
        self.assertEqual(metadata.read_bytes(), metadata_before)
        self.assertEqual(native.read_bytes(), native_before)
        self.assertEqual(migration.run(migration.parser().parse_args(args + ["--apply"]))["status"], "already_migrated")
        manifest = json.loads((Path(applied["transaction"]) / "manifest.json").read_text())
        self.assertEqual(manifest["dimension_overrides"], {str(directory): name})
        migration.run(migration.parser().parse_args(common + ["--rollback", applied["transaction"]]))
        after = {key: value for key, value in self.snapshot().items()
                 if not key.startswith(migration.TRANSACTION_DIR + "/")}
        self.assertEqual(after, before)


if __name__ == "__main__":
    unittest.main()
