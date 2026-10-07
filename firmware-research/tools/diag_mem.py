"""Dump the controller's flash / chip-ID area through the DIAG firmware's memory-read feature report and look for a serial number.

  python tools/diag_mem.py <serial> [out_dir]

Needs the DIAG build running (VID 054C / PID 05C4).  Read-only.  Writes flash.bin (0x08000000..0x0800FFFF), uid.bin
(0x1FFFF7A0..0x1FFFF81F) and ram.bin, then searches them for <serial> as ASCII, UTF-16, packed hex (both byte orders)
and as the three 32-bit UID words, so we can tell where the stock firmware gets the number printed on the sticker.
"""

from __future__ import annotations

import struct
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent / "python"))
import hid  # noqa: E402

VID, PID = 0x054C, 0x05C4
CHUNK = 63


def read_range(d: hid.device, start: int, end: int) -> bytes:
    out = bytearray()
    addr = start
    while addr < end:
        d.send_feature_report(bytes([0xF2]) + struct.pack("<I", addr) + bytes(11))
        rep = bytes(d.get_feature_report(0xB0, 64))
        if len(rep) < 64 or rep[0] != 0xB0:
            raise SystemExit(f"bad reply at {addr:#x}: {rep[:4].hex()}")
        out += rep[1 : 1 + CHUNK]
        addr += CHUNK
    return bytes(out[: end - start])


def variants(serial: str) -> dict[str, bytes]:
    v = {"ascii": serial.encode(), "utf16le": serial.encode("utf-16le")}
    if all(c in "0123456789abcdefABCDEF" for c in serial):
        h = serial if len(serial) % 2 == 0 else "0" + serial
        raw = bytes.fromhex(h)
        v["packed hex (big-endian)"] = raw
        v["packed hex (little-endian)"] = raw[::-1]
    return v


def main() -> None:
    serial = sys.argv[1]
    out = Path(sys.argv[2]) if len(sys.argv) > 2 else Path.cwd()
    out.mkdir(parents=True, exist_ok=True)
    d = hid.device()
    d.open(VID, PID)
    regions = {
        "flash.bin": (0x08000000, 0x08010000),
        "uid.bin": (0x1FFFF7A0, 0x1FFFF820),
        "ram.bin": (0x20000000, 0x20004000),
    }
    data = {}
    for name, (a, b) in regions.items():
        data[name] = read_range(d, a, b)
        (out / name).write_bytes(data[name])
        print(f"{name}: {len(data[name])} bytes from {a:#010x}")

    uid = data["uid.bin"]
    w = struct.unpack_from("<3I", uid, 0xAC - 0xA0)
    print("UID words (0x1FFFF7AC):", " ".join(f"{x:08X}" for x in w), "| as one hex string:", "".join(f"{x:08X}" for x in w))
    print("flash size KB (0x1FFFF7CC):", struct.unpack_from("<H", uid, 0xCC - 0xA0)[0])

    found = False
    for label, pat in variants(serial).items():
        for name, blob in data.items():
            at = blob.find(pat)
            while at >= 0:
                base = regions[name][0]
                print(f"FOUND {label} in {name} at {base + at:#010x}")
                found = True
                at = blob.find(pat, at + 1)
    if not found:
        print("serial not found verbatim; it is probably derived (compare with the UID words above)")


if __name__ == "__main__":
    main()
