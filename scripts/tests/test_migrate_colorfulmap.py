"""Offline migration integration tests against synthetic disposable saves."""

from __future__ import annotations

import gzip
import json
from pathlib import Path
import select
import struct
import subprocess
import sys
import tempfile
import unittest
from unittest import mock
import uuid
import zlib

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import migrate_colorfulmap as migration
from nbt_codec import Document, Tag, compound, dump, integer, parse, string, string_value


ARTWORK = "12345678-1234-4234-8234-123456789abc"
SECOND_ARTWORK = "87654321-4321-4321-8321-cba987654321"


def png(color=(24, 64, 128, 255)):
    """Produce a complete standard 128x128 RGBA PNG, without Pillow."""
    def chunk(kind, payload):
        return (struct.pack(">I", len(payload)) + kind + payload
                + struct.pack(">I", zlib.crc32(kind + payload) & 0xffffffff))

    rows = (b"\x00" + bytes(color) * 128) * 128
    return (b"\x89PNG\r\n\x1a\n"
            + chunk(b"IHDR", struct.pack(">IIBBBBB", 128, 128, 8, 6, 0, 0, 0))
            + chunk(b"IDAT", zlib.compress(rows)) + chunk(b"IEND", b""))


def tags(children):
    return Tag(9, (10, children))


def filled_map(identifier=47, *, modern=False, pdc=None):
    values = compound()
    if pdc is not None:
        values.set("PublicBukkitValues", compound([(migration.PDC_KEY, string(pdc))]))
    if modern:
        components = compound([("minecraft:map_id", integer(identifier))])
        if pdc is not None:
            components.set("minecraft:custom_data", values)
        return compound([("id", string("minecraft:filled_map")),
                         ("count", integer(1)), ("components", components)])
    values.set("map", integer(identifier))
    return compound([("id", string("minecraft:filled_map")),
                     ("Count", Tag(1, b"\x01")), ("tag", values)])


def item_path(item, modern=False):
    custom = item.get("components").get("minecraft:custom_data") if modern else item.get("tag")
    if custom is None or custom.get("PublicBukkitValues") is None:
        return None
    field = custom.get("PublicBukkitValues").get(migration.PDC_KEY)
    return string_value(field) if field is not None else None


def write_dat(path, root, codec="gzip"):
    path.parent.mkdir(parents=True, exist_ok=True)
    raw = dump(Document(b"", root))
    path.write_bytes(gzip.compress(raw, mtime=0) if codec == "gzip" else zlib.compress(raw) if codec == "zlib" else raw)
    return path


def read_dat(path):
    return migration.decode_dat(path.read_bytes())[0].root


def region_bytes(root):
    payload = zlib.compress(dump(Document(b"", root)))
    record = struct.pack(">I", len(payload) + 1) + b"\x02" + payload
    count = (len(record) + 4095) // 4096
    header = bytearray(8192)
    header[:4] = struct.pack(">I", (2 << 8) | count)
    header[4096:4100] = struct.pack(">I", 1234567)
    return bytes(header) + record.ljust(count * 4096, b"\x00")


def read_region(path):
    data = path.read_bytes()
    location = struct.unpack_from(">I", data)[0]
    offset = (location >> 8) * 4096
    length = struct.unpack_from(">I", data, offset)[0]
    assert data[offset + 4] == 2
    return parse(zlib.decompress(data[offset + 5:offset + 4 + length])).root


class MigrationIntegrationTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="test-colorfulmap-")
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.source = self.root / "plugins" / "ColorfulMap"
        self.target = self.root / "plugins" / "ReColorfulMap"
        self.world_uuid = uuid.UUID("aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee")
        self.world = self.make_world("world", self.world_uuid)
        self.reference = f"maps/{ARTWORK}/thumb.png"
        self.artwork(ARTWORK)
        self.index({"world": {47: self.reference}})
        self.player = self.world / "playerdata" / "00000000-0000-0000-0000-000000000001.dat"
        write_dat(self.player, compound([("Inventory", tags([filled_map()]))]))

    def make_world(self, name, identity):
        directory = self.root / name
        directory.mkdir()
        (directory / "session.lock").write_bytes(b"\xe2\x98\x83")
        (directory / "uid.dat").write_bytes(identity.bytes)
        write_dat(directory / "level.dat", compound([("Data", compound())]))
        return directory

    def artwork(self, identifier, color=(24, 64, 128, 255)):
        directory = self.source / "maps" / identifier
        directory.mkdir(parents=True, exist_ok=True)
        for name in ("thumb.png", "0_0.png"):
            (directory / name).write_bytes(png(color))
        return directory

    def index(self, entries):
        path = self.source / "maps" / "maps.yml"
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(migration.yaml.safe_dump(entries), encoding="utf-8")
        return path

    def arguments(self, *flags):
        return migration.parser().parse_args(["--server-root", str(self.root), *map(str, flags)])

    def run_migration(self, *flags):
        return migration.run(self.arguments(*flags))

    def snapshot(self, *, transactions=False):
        return {str(path.relative_to(self.root)): path.read_bytes()
                for path in self.root.rglob("*") if path.is_file()
                and (transactions or migration.TRANSACTION_DIR not in path.relative_to(self.root).parts)}

    def native_map(self, directory, identifier, identity, dimension="minecraft:overworld"):
        most, least = struct.unpack(">qq", identity.bytes)
        return write_dat(directory / "data" / f"map_{identifier}.dat", compound([
            ("data", compound([("UUIDMost", Tag(4, struct.pack(">q", most))),
                               ("UUIDLeast", Tag(4, struct.pack(">q", least))),
                               ("dimension", string(dimension))]))]))

    def test_dry_run_apply_repeat_and_rollback(self):
        before = self.snapshot(transactions=True)
        dry = self.run_migration()
        self.assertEqual(dry["status"], "dry_run")
        self.assertEqual(dry["migrated"], 1)
        self.assertEqual(dry["changed_files"], 3)
        self.assertEqual(self.snapshot(transactions=True), before)
        self.assertFalse(self.target.exists())

        result = self.run_migration("--apply")
        self.assertEqual(result["status"], "applied")
        self.assertEqual(result["migrated"], 1)
        inventory = read_dat(self.player).get("Inventory").value[1]
        self.assertEqual(item_path(inventory[0]), self.reference)
        self.assertEqual((self.target / self.reference).read_bytes(), png())
        self.assertTrue((self.source / "maps/maps.yml").is_file())
        self.assertFalse((self.target / "maps/maps.yml").exists())
        transaction = Path(result["transaction"])
        manifest = json.loads((transaction / "manifest.json").read_text())
        self.assertEqual(manifest["state"], "complete")

        after = self.snapshot(transactions=True)
        repeat = self.run_migration("--apply")
        self.assertEqual(repeat["status"], "already_migrated")
        self.assertEqual(repeat["transaction"], result["transaction"])
        self.assertEqual(self.snapshot(transactions=True), after)

        rollback = self.run_migration("--rollback", transaction)
        self.assertEqual(rollback["status"], "rolled_back")
        self.assertEqual(self.snapshot(), before)
        self.assertEqual(json.loads((transaction / "manifest.json").read_text())["state"], "rolled_back")

    def test_legacy_and_modern_maps_inside_nested_shulker_boxes(self):
        legacy = filled_map()
        modern = filled_map(modern=True)
        old_box = compound([("id", string("minecraft:shulker_box")),
                            ("Count", Tag(1, b"\x01")),
                            ("tag", compound([("BlockEntityTag", compound([("Items", tags([legacy]))]))]))])
        modern_box = compound([
            ("id", string("minecraft:purple_shulker_box")), ("count", integer(1)),
            ("components", compound([
                ("minecraft:container", tags([
                    compound([("slot", integer(0)), ("item", modern)]),
                    compound([("slot", integer(1)), ("item", old_box)]),
                ])),
                ("minecraft:custom_data", compound([
                    ("display", Tag(8, b"\x00\x06\xed\xa0\xbd\xed\xb8\x80")),
                ])),
            ])),
        ])
        write_dat(self.player, compound([("EnderItems", tags([modern_box]))]), codec="zlib")
        result = self.run_migration("--apply")
        self.assertEqual(result["migrated"], 2)
        box = read_dat(self.player).get("EnderItems").value[1][0]
        contents = box.get("components").get("minecraft:container").value[1]
        self.assertEqual(item_path(contents[0].get("item"), modern=True), self.reference)
        old_nested = contents[1].get("item").get("tag").get("BlockEntityTag").get("Items").value[1][0]
        self.assertEqual(item_path(old_nested), self.reference)
        self.assertEqual(box.get("components").get("minecraft:custom_data").get("display").value,
                         b"\x00\x06\xed\xa0\xbd\xed\xb8\x80")
        self.assertEqual(migration.decode_dat(self.player.read_bytes())[1], "zlib")

    def test_level_player_block_entity_and_item_frame_are_all_updated(self):
        write_dat(self.world / "level.dat", compound([
            ("Data", compound([("Player", compound([("Inventory", tags([filled_map(modern=True)]))]))]))]))
        block_region = self.world / "region" / "r.0.0.mca"
        block_region.parent.mkdir()
        block_region.write_bytes(region_bytes(compound([
            ("block_entities", tags([compound([("id", string("minecraft:chest")),
                                                ("Items", tags([filled_map()]))])]))])))
        entity_region = self.world / "DIM-1" / "entities" / "r.0.0.mca"
        entity_region.parent.mkdir(parents=True)
        entity_region.write_bytes(region_bytes(compound([
            ("Entities", tags([compound([("id", string("minecraft:item_frame")),
                                         ("Item", filled_map(modern=True))])]))])))
        result = self.run_migration("--apply")
        self.assertEqual(result["migrated"], 4)
        embedded = read_dat(self.world / "level.dat").get("Data").get("Player").get("Inventory").value[1][0]
        chest = read_region(block_region).get("block_entities").value[1][0].get("Items").value[1][0]
        frame = read_region(entity_region).get("Entities").value[1][0].get("Item")
        self.assertEqual(item_path(embedded, modern=True), self.reference)
        self.assertEqual(item_path(chest), self.reference)
        self.assertEqual(item_path(frame, modern=True), self.reference)
        self.assertEqual(block_region.read_bytes()[4096:4100], struct.pack(">I", 1234567))

    def test_unmatched_vanilla_map_bytes_remain_unchanged(self):
        vanilla = filled_map(999, modern=True)
        original = dump(Document(b"", vanilla))
        write_dat(self.player, compound([("Inventory", tags([filled_map(), vanilla]))]), codec="raw")
        result = self.run_migration("--apply")
        self.assertEqual(result["migrated"], 1)
        self.assertEqual(result["vanilla"], 1)
        restored = read_dat(self.player).get("Inventory").value[1][1]
        self.assertIsNone(item_path(restored, modern=True))
        self.assertEqual(dump(Document(b"", restored)), original)

    def test_existing_matching_pdc_is_idempotent_without_old_transaction(self):
        write_dat(self.player, compound([("Inventory", tags([filled_map(pdc=self.reference)]))]))
        original = self.player.read_bytes()
        result = self.run_migration("--apply")
        self.assertEqual(result["migrated"], 0)
        self.assertEqual(result["already"], 1)
        self.assertEqual(self.player.read_bytes(), original)

    def test_pdc_conflict_aborts_global_preflight_without_any_write(self):
        second = self.player.parent / "ffffffff-ffff-ffff-ffff-ffffffffffff.dat"
        write_dat(second, compound([("Inventory", tags([
            filled_map(modern=True, pdc=f"maps/{SECOND_ARTWORK}/thumb.png")]))]))
        before = self.snapshot(transactions=True)
        with self.assertRaisesRegex(migration.MigrationError, "PDC conflicts"):
            self.run_migration("--apply")
        self.assertEqual(self.snapshot(transactions=True), before)
        self.assertFalse(self.target.exists())

    def test_missing_image_aborts_without_any_write(self):
        (self.source / "maps" / ARTWORK / "0_0.png").unlink()
        before = self.snapshot(transactions=True)
        with self.assertRaisesRegex(migration.MigrationError, "Missing thumbnail or tiles"):
            self.run_migration("--apply")
        self.assertEqual(self.snapshot(transactions=True), before)

    def test_different_existing_target_file_aborts_without_overwriting(self):
        target_image = self.target / self.reference
        target_image.parent.mkdir(parents=True)
        target_image.write_bytes(png((255, 0, 0, 255)))
        before = self.snapshot(transactions=True)
        with self.assertRaisesRegex(migration.MigrationError, "different content"):
            self.run_migration("--apply")
        self.assertEqual(self.snapshot(transactions=True), before)

    def test_identical_target_images_are_reused(self):
        target_image = self.target / self.reference
        target_image.parent.mkdir(parents=True)
        target_image.write_bytes(png())
        result = self.run_migration("--apply")
        self.assertEqual(result["changed_files"], 2)
        self.assertEqual(target_image.read_bytes(), png())

    def test_in_place_removes_index_only_after_preflight_and_can_rollback(self):
        before = self.snapshot()
        target_argument = ("--target", "plugins/ColorfulMap")
        dry = self.run_migration(*target_argument)
        self.assertEqual(dry["changed_files"], 2)
        self.assertEqual(self.snapshot(), before)
        result = self.run_migration(*target_argument, "--apply")
        self.assertFalse((self.source / "maps/maps.yml").exists())
        self.assertEqual((self.source / self.reference).read_bytes(), png())
        self.assertEqual(self.run_migration(*target_argument, "--apply")["status"], "already_migrated")
        self.run_migration("--rollback", result["transaction"])
        self.assertEqual(self.snapshot(), before)

    @unittest.skipIf(sys.platform == "win32", "Migration uses POSIX Java-compatible locks")
    def test_session_lock_held_by_another_process_prevents_migration(self):
        child = subprocess.Popen([
            sys.executable, "-u", "-c",
            "import fcntl,sys; h=open(sys.argv[1], 'r+b'); "
            "fcntl.lockf(h, fcntl.LOCK_EX); print('locked', flush=True); sys.stdin.read()",
            str(self.world / "session.lock"),
        ], stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
        try:
            readable, _, _ = select.select([child.stdout], [], [], 5)
            self.assertTrue(readable, "Child did not acquire its lock promptly")
            self.assertEqual(child.stdout.readline().strip(), "locked")
            before = self.snapshot(transactions=True)
            with self.assertRaisesRegex(migration.MigrationError, "locked by a running server"):
                self.run_migration("--apply")
            self.assertEqual(self.snapshot(transactions=True), before)
        finally:
            child.communicate(timeout=5)
        self.assertEqual(child.returncode, 0)
        self.assertEqual(self.run_migration()["status"], "dry_run")

    def test_conflicting_index_worlds_resolved_by_native_uuid_not_item_location(self):
        identity = uuid.UUID("bbbbbbbb-cccc-4ddd-8eee-ffffffffffff")
        other_world = self.make_world("paint_world", identity)
        self.artwork(SECOND_ARTWORK, (64, 128, 24, 255))
        second_reference = f"maps/{SECOND_ARTWORK}/thumb.png"
        self.index({"world": {47: self.reference}, "paint_world": {47: second_reference}})
        # Paper may save a map in one world's data directory while its UUID
        # names another world. The item's player also lives in the first world.
        self.native_map(self.world, 47, identity)
        result = self.run_migration("--apply")
        self.assertEqual(result["migrated"], 1)
        self.assertEqual(result["unused_index_entries"], 1)
        self.assertEqual(item_path(read_dat(self.player).get("Inventory").value[1][0]), second_reference)
        self.assertTrue(other_world.is_dir())

    def test_unresolved_conflicting_world_indices_abort_instead_of_guessing(self):
        self.artwork(SECOND_ARTWORK)
        self.index({"world": {47: self.reference}, "elsewhere": {47: f"maps/{SECOND_ARTWORK}/thumb.png"}})
        before = self.snapshot(transactions=True)
        with self.assertRaisesRegex(migration.MigrationError, "ambiguous"):
            self.run_migration("--apply")
        self.assertEqual(self.snapshot(transactions=True), before)

    def test_native_other_world_prevents_stale_index_from_matching_vanilla_map(self):
        identity = uuid.UUID("bbbbbbbb-cccc-4ddd-8eee-ffffffffffff")
        self.make_world("other_world", identity)
        self.native_map(self.world, 47, identity)
        original = self.player.read_bytes()
        result = self.run_migration("--apply")
        self.assertEqual(result["migrated"], 0)
        self.assertEqual(result["vanilla"], 1)
        self.assertEqual(self.player.read_bytes(), original)

    def test_unknown_native_uuid_does_not_guess_using_unique_index(self):
        self.native_map(self.world, 47, uuid.UUID("11111111-2222-4333-8444-555555555555"),
                        dimension="example:missing_dimension")
        before = self.snapshot(transactions=True)
        with self.assertRaises(migration.MigrationError):
            self.run_migration("--apply")
        self.assertEqual(self.snapshot(transactions=True), before)

    def test_unknown_native_uuid_does_not_fall_back_to_overworld_dimension(self):
        self.native_map(self.world, 47, uuid.UUID("11111111-2222-4333-8444-555555555555"))
        before = self.snapshot(transactions=True)
        with self.assertRaises(migration.MigrationError):
            self.run_migration("--apply")
        self.assertEqual(self.snapshot(transactions=True), before)

    def test_26_2_namespaced_map_data_and_players_data_are_migrated(self):
        identity = uuid.UUID("bbbbbbbb-cccc-4ddd-8eee-ffffffffffff")
        self.make_world("paint_world", identity)
        self.artwork(SECOND_ARTWORK)
        second_reference = f"maps/{SECOND_ARTWORK}/thumb.png"
        self.index({"world": {47: self.reference}, "paint_world": {47: second_reference}})
        old_native = self.native_map(self.world, 47, identity)
        new_native = self.world / "data" / "minecraft" / "maps" / "47.dat"
        new_native.parent.mkdir(parents=True)
        old_native.rename(new_native)
        native_before = new_native.read_bytes()
        new_player = self.world / "players" / "data" / self.player.name
        new_player.parent.mkdir(parents=True)
        self.player.rename(new_player)
        before = self.snapshot()

        result = self.run_migration("--apply")
        self.assertEqual(result["migrated"], 1)
        self.assertEqual(item_path(read_dat(new_player).get("Inventory").value[1][0]), second_reference)
        self.assertEqual(new_native.read_bytes(), native_before)
        self.run_migration("--rollback", result["transaction"])
        self.assertEqual(self.snapshot(), before)

    def test_commit_io_failure_restores_all_already_written_files(self):
        before = self.snapshot()
        replace = migration.replace_bytes
        failed = False

        def fail_player_once(path, data, mode, owner=None):
            nonlocal failed
            if Path(path) == self.player and not failed:
                failed = True
                raise OSError("synthetic disk write failure")
            return replace(path, data, mode, owner)

        with mock.patch.object(migration, "replace_bytes", side_effect=fail_player_once):
            with self.assertRaisesRegex(migration.MigrationError, "synthetic disk write failure"):
                self.run_migration("--apply")
        self.assertTrue(failed)
        self.assertEqual(self.snapshot(), before)
        manifests = list((self.root / migration.TRANSACTION_DIR).glob("*/manifest.json"))
        self.assertEqual(len(manifests), 1)
        self.assertEqual(json.loads(manifests[0].read_text())["state"], "rolled_back")

    def test_rollback_refuses_post_migration_changes_before_restoring_anything(self):
        result = self.run_migration("--apply")
        self.player.write_bytes(self.player.read_bytes() + b"changed-after-migration")
        before = self.snapshot(transactions=True)
        with self.assertRaisesRegex(migration.MigrationError, "file changed since migration"):
            self.run_migration("--rollback", result["transaction"])
        self.assertEqual(self.snapshot(transactions=True), before)

    def test_repeat_refuses_player_restored_to_old_unmigrated_bytes(self):
        original = self.player.read_bytes()
        self.run_migration("--apply")
        self.player.write_bytes(original)
        before = self.snapshot(transactions=True)
        with self.assertRaisesRegex(migration.MigrationError, "subsequently changed"):
            self.run_migration("--apply")
        self.assertEqual(self.snapshot(transactions=True), before)

    def test_repeat_refuses_missing_target_image(self):
        self.run_migration("--apply")
        (self.target / self.reference).unlink()
        before = self.snapshot(transactions=True)
        with self.assertRaisesRegex(migration.MigrationError, "subsequently changed"):
            self.run_migration("--apply")
        self.assertEqual(self.snapshot(transactions=True), before)

    def test_repeat_refuses_newly_added_world(self):
        self.run_migration("--apply")
        self.make_world("new_world", uuid.UUID("11111111-2222-4333-8444-555555555555"))
        before = self.snapshot(transactions=True)
        with self.assertRaisesRegex(migration.MigrationError, "world selection differs"):
            self.run_migration("--apply")
        self.assertEqual(self.snapshot(transactions=True), before)

    def test_repeat_refuses_newly_added_player_save(self):
        self.run_migration("--apply")
        write_dat(self.player.parent / "ffffffff-ffff-ffff-ffff-ffffffffffff.dat",
                  compound([("Inventory", tags([filled_map()]))]))
        before = self.snapshot(transactions=True)
        with self.assertRaisesRegex(migration.MigrationError, "save-file set changed"):
            self.run_migration("--apply")
        self.assertEqual(self.snapshot(transactions=True), before)

    def test_plan_reobserving_changed_file_rejects_it_immediately(self):
        plan = migration.Plan(self.root / "stage")
        original = self.player.read_bytes()
        plan.observe(self.player, original)
        plan.observe(self.player, original)
        with self.assertRaises(migration.MigrationError):
            plan.observe(self.player, original + b"changed-between-reads")
        self.assertEqual(plan.observations[self.player], migration.digest(original))
        absent = self.root / "not-yet-created"
        plan.observe(absent, None)
        with self.assertRaises(migration.MigrationError):
            plan.observe(absent, b"appeared-between-reads")
        self.assertIsNone(plan.observations[absent])

    def test_commit_rolls_back_written_images_but_keeps_external_change_to_unwritten_save(self):
        before = self.snapshot()
        external = self.player.read_bytes() + b"external-edit-during-commit"
        replace = migration.replace_bytes
        injected = False

        def externally_modify_later_save(path, data, mode, owner=None):
            nonlocal injected
            result = replace(path, data, mode, owner)
            if Path(path) == self.target / self.reference and not injected:
                injected = True
                self.player.write_bytes(external)
            return result

        with mock.patch.object(migration, "replace_bytes", side_effect=externally_modify_later_save):
            with self.assertRaisesRegex(migration.MigrationError, "File changed during commit"):
                self.run_migration("--apply")
        self.assertTrue(injected)
        self.assertEqual(self.player.read_bytes(), external)
        before[str(self.player.relative_to(self.root))] = external
        self.assertEqual(self.snapshot(), before)
        manifests = list((self.root / migration.TRANSACTION_DIR).glob("*/manifest.json"))
        self.assertEqual(len(manifests), 1)
        self.assertEqual(json.loads(manifests[0].read_text())["state"], "rolled_back")

    def test_external_mcc_item_frame_migrates_and_rolls_back_without_changing_mca(self):
        region = self.world / "entities" / "r.0.0.mca"
        region.parent.mkdir()
        header = bytearray(8192)
        header[:4] = struct.pack(">I", (2 << 8) | 1)
        header[4096:4100] = struct.pack(">I", 1234567)
        region.write_bytes(bytes(header) + (struct.pack(">I", 1) + b"\x82").ljust(4096, b"\x00"))
        external = region.parent / "c.0.0.mcc"
        external.write_bytes(zlib.compress(dump(Document(b"", compound([
            ("Entities", tags([compound([("id", string("minecraft:item_frame")),
                                         ("Item", filled_map(modern=True))])]))])))))
        before = self.snapshot()
        mca_before = region.read_bytes()

        result = self.run_migration("--apply")
        self.assertEqual(result["migrated"], 2)
        restored = parse(zlib.decompress(external.read_bytes())).root
        item = restored.get("Entities").value[1][0].get("Item")
        self.assertEqual(item_path(item, modern=True), self.reference)
        self.assertEqual(region.read_bytes(), mca_before)
        self.assertEqual(self.run_migration("--apply")["status"], "already_migrated")
        self.run_migration("--rollback", result["transaction"])
        self.assertEqual(self.snapshot(), before)

    def test_bad_mca_aborts_preflight_without_writing_any_staged_changes(self):
        region = self.world / "region" / "r.0.0.mca"
        region.parent.mkdir()
        region.write_bytes(b"truncated-anvil-header")
        before = self.snapshot(transactions=True)
        with self.assertRaisesRegex(migration.RegionError, "truncated region header"):
            self.run_migration("--apply")
        self.assertEqual(self.snapshot(transactions=True), before)
        self.assertFalse(self.target.exists())
        self.assertFalse((self.root / migration.TRANSACTION_DIR).exists())

    def test_replacing_existing_file_preserves_its_owner_and_group(self):
        before = self.player.stat()
        migration.replace_bytes(self.player, b"replacement-bytes", 0o640)
        after = self.player.stat()
        self.assertEqual((after.st_uid, after.st_gid), (before.st_uid, before.st_gid))
        self.assertEqual(after.st_mode & 0o777, 0o640)
        self.assertEqual(self.player.read_bytes(), b"replacement-bytes")

    def test_apply_and_rollback_preserve_save_owner_group_and_permissions(self):
        self.player.chmod(0o600)
        original = self.player.stat()
        expected = (original.st_uid, original.st_gid, original.st_mode & 0o777)
        result = self.run_migration("--apply")
        migrated = self.player.stat()
        self.assertEqual((migrated.st_uid, migrated.st_gid, migrated.st_mode & 0o777), expected)
        self.run_migration("--rollback", result["transaction"])
        restored = self.player.stat()
        self.assertEqual((restored.st_uid, restored.st_gid, restored.st_mode & 0o777), expected)


if __name__ == "__main__":
    unittest.main()
