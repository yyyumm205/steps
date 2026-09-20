"""Bound ZIP directory parsing before ZipFile allocates all of its entries."""

import struct

from .schema import require


END = struct.Struct("<4s4H2LH")
ZIP64_LOCATOR = struct.Struct("<4sLQL")
ZIP64_END = struct.Struct("<4sQ2H2L4Q")
DIRECTORY_ENTRY = struct.Struct("<4s6H3L5H2L")


def check_zip_directory(path, maximum_entries):
    """Stream the bounded central directory; entry content is checked by ZipFile.

    Android emits ordinary flat archives. ZIP64 is also accepted, with consistent
    offsets and a single disk. Trailing junk is outside the upload contract.
    No payloads or per-entry objects are allocated here.
    """
    size = path.stat().st_size
    with path.open("rb") as stream:
        stream.seek(max(0, size - END.size - 65535))
        tail = stream.read(END.size + 65535)
        index = tail.rfind(b"PK\x05\x06")
        require(index >= 0 and len(tail) - index >= END.size, "missing ZIP directory footer")
        _, disk, directory_disk, disk_entries, entries, length, offset, comment = END.unpack_from(tail, index)
        require(index + END.size + comment == len(tail), "invalid ZIP footer length")
        require(disk == directory_disk == 0 and disk_entries == entries, "multidisk ZIP unsupported")
        end = size - len(tail) + index

        # ZIP64 end records precede the ordinary footer. Read a fixed-size header,
        # then validate its extent without allocating the optional extension.
        locator = b""
        if end >= ZIP64_LOCATOR.size:
            stream.seek(end - ZIP64_LOCATOR.size)
            locator = stream.read(ZIP64_LOCATOR.size)
        if locator.startswith(b"PK\x06\x07"):
            _, zip64_disk, zip64_offset, disks = ZIP64_LOCATOR.unpack(locator)
            require(zip64_disk == 0 and disks == 1, "multidisk ZIP64 unsupported")
            require(0 <= zip64_offset <= end - ZIP64_LOCATOR.size - ZIP64_END.size,
                    "invalid ZIP64 footer offset")
            stream.seek(zip64_offset)
            fields = ZIP64_END.unpack(stream.read(ZIP64_END.size))
            require(fields[0] == b"PK\x06\x06" and fields[1] >= 44 and
                    zip64_offset + 12 + fields[1] == end - ZIP64_LOCATOR.size,
                    "invalid ZIP64 footer length")
            require(fields[4] == fields[5] == 0 and fields[6] == fields[7], "multidisk ZIP64 unsupported")
            require(entries in (65535, fields[7]) and length in (0xFFFFFFFF, fields[8]) and
                    offset in (0xFFFFFFFF, fields[9]), "ZIP64 directory differs from footer")
            entries, length, offset, end = fields[7], fields[8], fields[9], zip64_offset
        else:
            require(entries != 65535 and length != 0xFFFFFFFF and offset != 0xFFFFFFFF,
                    "missing ZIP64 footer")

        require(0 < entries <= maximum_entries, "ZIP entry quota exceeded")
        require(0 <= offset <= end and offset + length == end, "invalid ZIP directory extent")
        stream.seek(offset)
        count = 0
        while stream.tell() < end:
            count += 1
            require(count <= maximum_entries, "ZIP entry quota exceeded")
            require(end - stream.tell() >= DIRECTORY_ENTRY.size, "truncated ZIP directory entry")
            fields = DIRECTORY_ENTRY.unpack(stream.read(DIRECTORY_ENTRY.size))
            require(fields[0] == b"PK\x01\x02", "invalid ZIP directory signature")
            name_length, extra_length, comment_length = fields[10:13]
            require(0 < name_length <= 160, "ZIP filename exceeds portable name limit")
            require(fields[13] == 0, "multidisk ZIP entry unsupported")
            following = stream.tell() + name_length + extra_length + comment_length
            require(following <= end, "ZIP directory entry crosses its boundary")
            stream.seek(following)
        require(count == entries, "ZIP directory entry count mismatch")
