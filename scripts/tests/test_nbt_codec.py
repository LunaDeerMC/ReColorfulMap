"""Tests use only synthetic NBT; no real Minecraft save files are touched."""

import pathlib
import struct
import sys
import unittest

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1]))
from nbt_codec import (
    BYTE, SHORT, INT, LONG, FLOAT, DOUBLE, BYTE_ARRAY, STRING, LIST,
    COMPOUND, INT_ARRAY, LONG_ARRAY, Document, NBTError, Tag, compound,
    dump, int_value, integer, parse, string, string_value,
)


def raw_string(value):
    return Tag(STRING, struct.pack(">H", len(value)) + value)


def document(*entries):
    return Document(b"", compound(entries))


class NBTCodecTest(unittest.TestCase):
    def test_modified_utf8_round_trip_keeps_emoji_null_and_names(self):
        # Java encodes U+1F600 as two UTF-16 surrogates and NUL as C0 80.
        modified = b"before\xed\xa0\xbd\xed\xb8\x80\xc0\x80after"
        doc = Document(modified, compound([(modified, raw_string(modified))]))
        binary = dump(doc)
        self.assertEqual(dump(parse(binary)), binary)
        self.assertEqual(parse(binary).root.items()[0][0], modified)
        with self.assertRaises(NBTError):
            string_value(parse(binary).root.items()[0][1])

    def test_every_type_preserved_including_nan_and_empty_list_type(self):
        tags = [
            Tag(BYTE, b"\x80"), Tag(SHORT, b"\x80\x01"),
            integer(-9001), Tag(LONG, struct.pack(">q", -2**63)),
            Tag(FLOAT, bytes.fromhex("7fc00013")),
            Tag(DOUBLE, bytes.fromhex("fff8000000000077")),
            Tag(BYTE_ARRAY, struct.pack(">i", 3) + b"\xff\x00\x80"),
            raw_string(b"\xed\xa0\xbd\xed\xb8\x80"),
            Tag(LIST, (INT, [integer(5), integer(-7)])),
            compound([(b"nested", string("value"))]),
            Tag(INT_ARRAY, struct.pack(">iii", 2, -1, 7)),
            Tag(LONG_ARRAY, struct.pack(">iqq", 2, -9, 11)),
            Tag(LIST, (0, [])), Tag(LIST, (LONG_ARRAY, [])),
        ]
        doc = document(*[(str(i), tag) for i, tag in enumerate(tags)])
        data = dump(doc)
        self.assertEqual(dump(parse(data)), data)
        self.assertEqual(parse(data), doc)

    def test_adding_pdc_preserves_original_payloads_and_order(self):
        original = compound([
            (b"id", string("minecraft:filled_map")),
            (b"tag", compound([
                (b"display", compound([(b"Name", raw_string(b"\xed\xa0\xbd\xed\xb8\x80"))])),
                (b"map", integer(47)),
            ])),
        ])
        data = dump(Document(b"root", original))
        edited = parse(data)
        item_tag = edited.root.get("tag")
        name_before = dump(Document(b"", item_tag.get("display")))
        item_tag.set("PublicBukkitValues", compound([
            (b"recolorfulmap:image_path", string("maps/1234/thumb.png")),
        ]))
        result = parse(dump(edited))
        self.assertEqual(dump(Document(b"", result.root.get("tag").get("display"))), name_before)
        self.assertEqual(int_value(result.root.get("tag").get("map")), 47)
        self.assertEqual([name for name, _ in result.root.items()], [b"id", b"tag"])
        self.assertEqual(string_value(result.root.get("tag").get("PublicBukkitValues").get("recolorfulmap:image_path")), "maps/1234/thumb.png")

    def test_duplicate_names_round_trip_but_targeted_edit_fails(self):
        doc = document((b"other", integer(3)), (b"a", integer(1)), (b"a", integer(2)))
        binary = dump(doc)
        restored = parse(binary)
        self.assertEqual(dump(restored), binary)
        self.assertEqual(int_value(restored.root.get("other")), 3)
        with self.assertRaises(NBTError):
            restored.root.get("a")
        with self.assertRaises(NBTError):
            restored.root.set("a", integer(9))
        restored.root.set("other", integer(10))
        self.assertEqual(restored.root.items()[0], (b"other", integer(10)))
        self.assertIsNone(restored.root.get("missing"))

    def test_traversal(self):
        a, b = integer(1), integer(2)
        self.assertEqual(list(compound([(b"a", a), (b"b", b)]).children()), [a, b])
        self.assertEqual(list(Tag(LIST, (INT, [a, b])).children()), [a, b])
        self.assertEqual(list(a.children()), [])
        with self.assertRaises(NBTError):
            a.get("field")

    def test_integer_types_and_text_helpers(self):
        for kind, fmt, value in [(BYTE, ">b", -1), (SHORT, ">h", -321),
                                 (INT, ">i", -12345), (LONG, ">q", -2**62)]:
            with self.subTest(kind=kind):
                self.assertEqual(int_value(Tag(kind, struct.pack(fmt, value))), value)
        self.assertEqual(string_value(string("地图/maps/0_0.png")), "地图/maps/0_0.png")
        with self.assertRaises(NBTError):
            string_value(integer(1))
        with self.assertRaises(NBTError):
            int_value(string("1"))
        with self.assertRaises(NBTError):
            integer(2**31)
        with self.assertRaises(NBTError):
            string("a" * 65536)

    def test_reject_bad_root_and_trailing_bytes(self):
        for data in [b"", b"\x00", b"\x03\x00\x00\x00\x00\x00\x01",
                     b"\x0a\x00\x00\x00extra", b"\x0a\x00\x00\xff"]:
            with self.subTest(data=data), self.assertRaises(NBTError):
                parse(data)

    def test_truncation_at_every_offset(self):
        data = dump(document((b"items", Tag(LIST, (COMPOUND, [compound([(b"map", integer(2))])])))))
        for end in range(len(data)):
            with self.subTest(end=end), self.assertRaises(NBTError):
                parse(data[:end])

    def test_negative_array_and_list_lengths_rejected(self):
        for kind in [BYTE_ARRAY, INT_ARRAY, LONG_ARRAY, LIST]:
            payload = (b"\x03" if kind == LIST else b"") + struct.pack(">i", -1)
            data = b"\x0a\x00\x00" + bytes([kind]) + b"\x00\x01a" + payload + b"\x00"
            with self.subTest(kind=kind), self.assertRaises(NBTError):
                parse(data)

    def test_invalid_list_element_type_and_nonempty_end_list(self):
        for element_kind, count in [(0, 1), (13, 0), (255, 0)]:
            data = b"\x0a\x00\x00\x09\x00\x01a" + bytes([element_kind]) + struct.pack(">i", count) + b"\x00"
            with self.subTest(element_kind=element_kind), self.assertRaises(NBTError):
                parse(data)

    def test_bounded_depth_tag_count_and_bytes(self):
        root = compound()
        cursor = root
        for _ in range(20):
            child = compound()
            cursor.set("nested", child)
            cursor = child
        data = dump(Document(b"", root))
        self.assertEqual(dump(parse(data, max_depth=20)), data)
        for options in [{"max_depth": 19}, {"max_tags": 20}, {"max_bytes": len(data) - 1}]:
            with self.subTest(options=options), self.assertRaises(NBTError):
                parse(data, **options)
        with self.assertRaises(NBTError):
            dump(Document(b"", root), max_depth=19)
        huge_list = b"\x0a\x00\x00\x09\x00\x01a\x03\x7f\xff\xff\xff\x00"
        with self.assertRaises(NBTError):
            parse(huge_list)

    def test_invalid_hand_built_payloads_rejected(self):
        tags = [Tag(INT, b"\x00"), Tag(STRING, b"\x00\x02x"),
                Tag(BYTE_ARRAY, struct.pack(">i", -1)),
                Tag(INT_ARRAY, struct.pack(">i", 2) + b"\x00" * 4),
                Tag(LIST, (INT, [string("wrong")])), Tag(LIST, (0, [integer(1)])),
                Tag(LIST, (13, [])), Tag(13, b""), Tag(0, b"")]
        for tag in tags:
            with self.subTest(tag=tag), self.assertRaises(NBTError):
                dump(document((b"field", tag)))

    def test_huge_array_lengths_do_not_allocate(self):
        for kind in [BYTE_ARRAY, INT_ARRAY, LONG_ARRAY]:
            data = b"\x0a\x00\x00" + bytes([kind]) + b"\x00\x00\x7f\xff\xff\xff\x00"
            with self.subTest(kind=kind), self.assertRaises(NBTError):
                parse(data)


if __name__ == "__main__":
    unittest.main()
