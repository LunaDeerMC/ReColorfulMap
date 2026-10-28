"""Conservative byte prefilter for supported image-map NBT representations."""
from pathlib import Path
import sys
import unittest
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import migrate_colorfulmap as migration
from nbt_codec import Document, compound, dump, parse, string
from test_migrate_colorfulmap import filled_map, item_path, tags


class RegionItemFilterTests(unittest.TestCase):
    def test_mapless_terrain_does_not_build_nbt_objects(self):
        raw = dump(Document(b"", compound([("terrain", string("stone"))])))
        resolver = mock.Mock()
        with mock.patch.object(migration, "parse", side_effect=AssertionError("must skip")):
            self.assertIsNone(migration.migrate_region_chunk(raw, "terrain", resolver, migration.Counts()))
        resolver.resolve.assert_not_called()

    def test_old_and_modern_nested_items_are_never_skipped(self):
        reference = "maps/12345678-1234-4234-8234-123456789abc/thumb.png"
        for modern in (False, True):
            with self.subTest(modern=modern):
                raw = dump(Document(b"", compound([("arbitrary", tags([
                    compound([("container", tags([filled_map(modern=modern)]))])]))])))
                resolver = mock.Mock()
                resolver.resolve.return_value = reference
                counts = migration.Counts()
                result = migration.migrate_region_chunk(raw, "nested", resolver, counts)
                container = next(parse(result).root.get("arbitrary").children())
                item = next(container.get("container").children())
                self.assertEqual(item_path(item, modern=modern), reference)
                self.assertEqual(counts.migrated, 1)
                resolver.resolve.assert_called_once_with(47)

    def test_false_positive_text_is_parsed_but_not_transformed(self):
        raw = dump(Document(b"", compound([("name", string("filled_map decoration"))])))
        resolver = mock.Mock()
        self.assertIsNone(migration.migrate_region_chunk(raw, "unrelated", resolver, migration.Counts()))
        resolver.resolve.assert_not_called()

    def test_damaged_candidate_nbt_reports_chunk_context(self):
        with self.assertRaisesRegex(migration.MigrationError, "chunk-context"):
            migration.migrate_region_chunk(b"filled_map invalid NBT", "chunk-context", mock.Mock(), migration.Counts())


if __name__ == "__main__":
    unittest.main()
