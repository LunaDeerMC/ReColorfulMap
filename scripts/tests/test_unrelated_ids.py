"""Unknown custom NBT must survive matching a real map elsewhere in a save."""
from pathlib import Path
import struct
import sys
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from migrate_colorfulmap import Counts, Resolver, migrate_document
from nbt_codec import Document, Tag, compound, dump, integer, string, string_value


class UnrelatedIdTest(unittest.TestCase):
    def test_unknown_id_with_java_modified_utf8_is_not_decoded(self):
        reference = "maps/aabbccdd-0000-4000-8000-000000000001/thumb.png"
        # Java writeUTF stores the surrogate pair for 😀 and encodes NUL as C0 80.
        raw = b"emoji \xed\xa0\xbd\xed\xb8\x80\xc0\x80"
        unknown = compound([("id", Tag(8, struct.pack(">H", len(raw)) + raw)), ("value", integer(42))])
        untouched = dump(Document(b"", unknown))
        item = compound([("id", string("minecraft:filled_map")), ("count", integer(1)),
                         ("components", compound([("minecraft:map_id", integer(47)),
                                                   ("minecraft:custom_data", compound([("unrelated", unknown)]))]))])
        document = Document(b"", compound([("Item", item)]))
        resolver = Resolver({("world", 47): reference}, {}, [])
        counts = Counts()
        self.assertTrue(migrate_document(document, resolver, counts))
        self.assertEqual(counts.migrated, 1)
        custom = item.get("components").get("minecraft:custom_data")
        self.assertEqual(dump(Document(b"", custom.get("unrelated"))), untouched)
        self.assertEqual(string_value(custom.get("PublicBukkitValues").get("re_colorful_map:image_path")), reference)


if __name__ == "__main__":
    unittest.main()
