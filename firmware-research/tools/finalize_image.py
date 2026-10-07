"""Stamp the config-block CRC into a built firmware image and sanity-check it.

  python tools/finalize_image.py kishi_ds4.bin

Finds the block by its 12-byte marker (magic + version + size; it must occur exactly once - the bare
8-byte magic may also show up as a compiler literal), writes CRC-32
(ISO-HDLC) over block bytes 16..255 at offset +12, and verifies the vector table lies inside the
application region (0x08003000..).  Used by ds4-firmware/build.sh; KishiDS does the same when
it patches settings.
"""

from __future__ import annotations

import binascii
import struct
import sys
from pathlib import Path

MAGIC = b"KISHICFG"
BASE = 0x08003000
BLOCK = 256
VERSION = 1
APP_LIMIT = 0x0800E000   # log page (DIAG) starts here; stock settings page is 0x0800F800


def main() -> None:
    path = Path(sys.argv[1])
    data = bytearray(path.read_bytes())

    marker = MAGIC + struct.pack("<HH", VERSION, BLOCK)
    first = data.find(marker)
    if first < 0:
        sys.exit("finalize: config block marker not found")
    if data.find(marker, first + 1) >= 0:
        sys.exit("finalize: config block marker appears more than once")

    crc = binascii.crc32(bytes(data[first + 16 : first + BLOCK])) & 0xFFFFFFFF
    struct.pack_into("<I", data, first + 12, crc)
    path.write_bytes(data)

    end = BASE + len(data)
    vec = struct.unpack_from("<48I", data, 0)
    targets = sorted({v for v in vec[1:] if v})
    ok = all(BASE <= (t & ~1) < end for t in targets)
    if not ok or end > APP_LIMIT:
        sys.exit(f"finalize: image does not fit the application region (end {end:#x})")
    print(f"finalize: config block at {first:#06x}, crc32 {crc:#010x}; image {len(data)} bytes ends {end:#x}")


if __name__ == "__main__":
    main()
