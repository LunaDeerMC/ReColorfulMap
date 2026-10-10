"""Small lossless NBT codec for offline save-file edits.

Names and primitive payloads stay as bytes. In particular, parsing never
decodes Java's modified UTF-8: unrelated strings survive byte for byte.
STRING payloads include their unsigned-short length; ARRAY payloads include
their signed-int count. Only explicit ``string_value`` calls decode text.
The codec handles uncompressed NBT; callers own compression and file I/O.
"""

from __future__ import annotations

from dataclasses import dataclass
import struct
from typing import Iterable, Iterator


END, BYTE, SHORT, INT, LONG, FLOAT, DOUBLE, BYTE_ARRAY, STRING, LIST, COMPOUND, INT_ARRAY, LONG_ARRAY = range(13)
DEFAULT_MAX_DEPTH = 512
DEFAULT_MAX_TAGS = 1_000_000
DEFAULT_MAX_BYTES = 256 * 1024 * 1024
_WIDTHS = {BYTE: 1, SHORT: 2, INT: 4, LONG: 8, FLOAT: 4, DOUBLE: 8}
_ARRAY_WIDTHS = {BYTE_ARRAY: 1, INT_ARRAY: 4, LONG_ARRAY: 8}


class NBTError(ValueError):
    """Malformed NBT or an ambiguous/invalid requested modification."""


def _name_bytes(name: str | bytes) -> bytes:
    if isinstance(name, str):
        return name.encode("utf-8")
    if isinstance(name, bytes):
        return name
    raise NBTError("NBT names must be str or bytes")


@dataclass
class Tag:
    kind: int
    value: object

    def items(self) -> list[tuple[bytes, Tag]]:
        """Return compound entries without decoding or collapsing names."""
        if self.kind != COMPOUND or not isinstance(self.value, list):
            raise NBTError("Expected a compound tag")
        return self.value

    def get(self, name: str | bytes) -> Tag | None:
        """Read a unique field, rejecting duplicate occurrences of that name."""
        key = _name_bytes(name)
        matches = [tag for field, tag in self.items() if field == key]
        if len(matches) > 1:
            raise NBTError(f"Duplicate compound field: {key!r}")
        return matches[0] if matches else None

    def set(self, name: str | bytes, tag: Tag) -> None:
        """Replace in place or append, preserving every other entry's order."""
        key = _name_bytes(name)
        if len(key) > 65535:
            raise NBTError("NBT name exceeds 65535 bytes")
        if not isinstance(tag, Tag) or tag.kind == END:
            raise NBTError("Compound fields must be non-END tags")
        entries = self.items()
        matches = [index for index, (field, _) in enumerate(entries) if field == key]
        if len(matches) > 1:
            raise NBTError(f"Duplicate compound field: {key!r}")
        if matches:
            entries[matches[0]] = (key, tag)
        else:
            entries.append((key, tag))

    def children(self) -> Iterator[Tag]:
        """Yield direct compound/list children; primitive tags have none."""
        if self.kind == COMPOUND:
            for _, tag in self.items():
                yield tag
        elif self.kind == LIST:
            yield from self.value[1]


@dataclass
class Document:
    name: bytes
    root: Tag


class _Reader:
    def __init__(self, data: bytes, max_depth: int, max_tags: int):
        self.data = data
        self.offset = 0
        self.max_depth = max_depth
        self.max_tags = max_tags
        self.tags = 0

    def take(self, length: int) -> bytes:
        end = self.offset + length
        if length < 0 or end > len(self.data):
            raise NBTError(f"Truncated NBT at byte {self.offset}")
        value = self.data[self.offset:end]
        self.offset = end
        return value

    def byte(self) -> int:
        return self.take(1)[0]

    def count(self) -> int:
        value = struct.unpack(">i", self.take(4))[0]
        if value < 0:
            raise NBTError("Negative NBT list/array length")
        return value

    def name(self) -> bytes:
        length = struct.unpack(">H", self.take(2))[0]
        return self.take(length)

    def tag(self, kind: int, depth: int) -> Tag:
        if kind not in range(1, 13):
            raise NBTError(f"Invalid NBT type: {kind}")
        if depth > self.max_depth:
            raise NBTError(f"NBT nesting exceeds {self.max_depth}")
        self.tags += 1
        if self.tags > self.max_tags:
            raise NBTError(f"NBT tag count exceeds {self.max_tags}")
        start = self.offset
        if kind in _WIDTHS:
            return Tag(kind, self.take(_WIDTHS[kind]))
        if kind == STRING:
            self.name()
            return Tag(kind, self.data[start:self.offset])
        if kind in _ARRAY_WIDTHS:
            count = self.count()
            self.take(count * _ARRAY_WIDTHS[kind])
            return Tag(kind, self.data[start:self.offset])
        if kind == LIST:
            element_kind = self.byte()
            if element_kind not in range(13):
                raise NBTError(f"Invalid NBT list element type: {element_kind}")
            count = self.count()
            if element_kind == END and count:
                raise NBTError("Nonempty NBT list cannot have END element type")
            if count > self.max_tags - self.tags:
                raise NBTError(f"NBT tag count exceeds {self.max_tags}")
            children = []
            for _ in range(count):
                children.append(self.tag(element_kind, depth + 1))
            return Tag(kind, (element_kind, children))
        entries = []
        while True:
            child_kind = self.byte()
            if child_kind == END:
                return Tag(kind, entries)
            if child_kind not in range(1, 13):
                raise NBTError(f"Invalid NBT type: {child_kind}")
            name = self.name()
            entries.append((name, self.tag(child_kind, depth + 1)))


def parse(data: bytes, *, max_depth: int = DEFAULT_MAX_DEPTH,
          max_tags: int = DEFAULT_MAX_TAGS,
          max_bytes: int = DEFAULT_MAX_BYTES) -> Document:
    """Parse exactly one compound-root NBT document, with bounded resources."""
    if not isinstance(data, bytes):
        raise NBTError("NBT input must be bytes")
    if max_depth < 0 or max_tags < 1 or max_bytes < 1:
        raise NBTError("Invalid NBT resource limits")
    if len(data) > max_bytes:
        raise NBTError(f"NBT input exceeds {max_bytes} bytes")
    reader = _Reader(data, max_depth, max_tags)
    if reader.byte() != COMPOUND:
        raise NBTError("NBT root must be a compound")
    name = reader.name()
    try:
        root = reader.tag(COMPOUND, 0)
    except RecursionError as error:
        raise NBTError("NBT nesting exceeds Python recursion capacity") from error
    if reader.offset != len(data):
        raise NBTError(f"Trailing bytes after NBT at byte {reader.offset}")
    return Document(name, root)


def _validate_payload(tag: Tag) -> bytes:
    payload = tag.value
    if not isinstance(payload, bytes):
        raise NBTError("Primitive NBT payload must be bytes")
    if tag.kind in _WIDTHS:
        if len(payload) != _WIDTHS[tag.kind]:
            raise NBTError("Wrong primitive NBT payload length")
    elif tag.kind == STRING:
        if len(payload) < 2 or struct.unpack(">H", payload[:2])[0] != len(payload) - 2:
            raise NBTError("Wrong NBT string payload length")
    elif tag.kind in _ARRAY_WIDTHS:
        if len(payload) < 4:
            raise NBTError("Truncated NBT array length")
        count = struct.unpack(">i", payload[:4])[0]
        if count < 0 or count * _ARRAY_WIDTHS[tag.kind] != len(payload) - 4:
            raise NBTError("Wrong NBT array payload length")
    else:
        raise NBTError(f"Invalid primitive NBT type: {tag.kind}")
    return payload


def dump(document: Document, *, max_depth: int = DEFAULT_MAX_DEPTH) -> bytes:
    """Serialize losslessly; also reject invalid hand-constructed trees."""
    if not isinstance(document, Document) or document.root.kind != COMPOUND:
        raise NBTError("NBT root must be a compound")
    if max_depth < 0:
        raise NBTError("Invalid NBT maximum depth")
    output = bytearray()

    def name(value: bytes) -> None:
        if not isinstance(value, bytes) or len(value) > 65535:
            raise NBTError("NBT names must be bytes of length at most 65535")
        output.extend(struct.pack(">H", len(value)))
        output.extend(value)

    def write(tag: Tag, depth: int) -> None:
        if not isinstance(tag, Tag) or tag.kind not in range(1, 13):
            raise NBTError("Invalid NBT tag")
        if depth > max_depth:
            raise NBTError(f"NBT nesting exceeds {max_depth}")
        if tag.kind == COMPOUND:
            for entry_name, child in tag.items():
                if not isinstance(child, Tag) or child.kind not in range(1, 13):
                    raise NBTError("Invalid NBT compound child")
                output.append(child.kind)
                name(entry_name)
                write(child, depth + 1)
            output.append(END)
        elif tag.kind == LIST:
            value = tag.value
            if not isinstance(value, tuple) or len(value) != 2:
                raise NBTError("NBT list value must be (element_kind, children)")
            element_kind, children = value
            if not isinstance(element_kind, int) or element_kind not in range(13):
                raise NBTError("Invalid NBT list element type")
            if not isinstance(children, list) or len(children) > 0x7fffffff:
                raise NBTError("Invalid NBT list children")
            if children and element_kind == END:
                raise NBTError("Nonempty NBT list cannot have END element type")
            output.append(element_kind)
            output.extend(struct.pack(">i", len(children)))
            for child in children:
                if not isinstance(child, Tag) or child.kind != element_kind:
                    raise NBTError("NBT list contains a different element type")
                write(child, depth + 1)
        else:
            output.extend(_validate_payload(tag))

    output.append(COMPOUND)
    name(document.name)
    try:
        write(document.root, 0)
    except RecursionError as error:
        raise NBTError("NBT nesting exceeds Python recursion capacity") from error
    return bytes(output)


def compound(entries: Iterable[tuple[str | bytes, Tag]] = ()) -> Tag:
    """Construct a compound, retaining supplied entry order and duplicates."""
    return Tag(COMPOUND, [(_name_bytes(name), tag) for name, tag in entries])


def string(value: str) -> Tag:
    """Construct a UTF-8 string for new identifiers/paths (normally ASCII)."""
    try:
        raw = value.encode("utf-8")
    except (AttributeError, UnicodeEncodeError) as error:
        raise NBTError("NBT text helper requires UTF-8 encodable str") from error
    if len(raw) > 65535:
        raise NBTError("NBT string exceeds 65535 bytes")
    return Tag(STRING, struct.pack(">H", len(raw)) + raw)


def integer(value: int) -> Tag:
    """Construct a signed 32-bit NBT INT."""
    try:
        return Tag(INT, struct.pack(">i", value))
    except (struct.error, TypeError) as error:
        raise NBTError("NBT INT value must be a signed 32-bit integer") from error


def string_value(tag: Tag) -> str:
    """Decode a selected UTF-8 identifier/path; never silently replace bytes."""
    if not isinstance(tag, Tag) or tag.kind != STRING:
        raise NBTError("Expected an NBT string")
    payload = _validate_payload(tag)
    try:
        return payload[2:].decode("utf-8", "strict")
    except UnicodeDecodeError as error:
        raise NBTError("Selected NBT string is not ordinary UTF-8") from error


def int_value(tag: Tag) -> int:
    """Decode a selected signed BYTE, SHORT, INT, or LONG."""
    formats = {BYTE: ">b", SHORT: ">h", INT: ">i", LONG: ">q"}
    if not isinstance(tag, Tag) or tag.kind not in formats:
        raise NBTError("Expected an integer NBT tag")
    return struct.unpack(formats[tag.kind], _validate_payload(tag))[0]
