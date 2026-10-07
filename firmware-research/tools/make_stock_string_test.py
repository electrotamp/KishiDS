"""Make a one-byte, descriptor-only Kishi v2.70 DFU validation image.

This does not communicate with a USB device.  The intended transfer decision is
left to the operator after checking the printed assertions.
"""

from __future__ import annotations

import hashlib
from pathlib import Path


SOURCE = Path("firmware-research/device-backups/kishi-stock-v2.70-recovery.bin")
OUTPUT = Path("firmware-research/experiments/stock-v2.70-product-string-test.bin")
SOURCE_SHA256 = "aa915363c858e5a06fb1ad15a82d059d50fd22a625d0663a3ce99fcab24f69be"
OFFSET = 0x6AA8  # final ASCII character in the NUL-terminated product string "Kishi"


def main() -> None:
    source = bytearray(SOURCE.read_bytes())
    if hashlib.sha256(source).hexdigest() != SOURCE_SHA256:
        raise SystemExit("Refusing: source is not the verified v2.70 recovery image.")
    if bytes(source[OFFSET - 4 : OFFSET + 1]) != b"Kishi":
        raise SystemExit("Refusing: expected product string is not at the verified offset.")

    test = source[:]
    test[OFFSET] = ord("x")
    differences = [index for index, (before, after) in enumerate(zip(source, test)) if before != after]
    if differences != [OFFSET] or len(test) != len(source):
        raise SystemExit("Refusing: output is not a one-byte, same-length mutation.")

    OUTPUT.parent.mkdir(exist_ok=True)
    OUTPUT.write_bytes(test)
    print(f"Source: {SOURCE} ({len(source)} bytes, SHA-256 {SOURCE_SHA256})")
    print(f"Output: {OUTPUT} ({len(test)} bytes, SHA-256 {hashlib.sha256(test).hexdigest()})")
    print(f"Only byte changed: 0x{OFFSET:04X}, 'i' (0x69) -> 'x' (0x78)")
    print("Expected observable result if accepted: USB product name Kishi becomes Kishx.")


if __name__ == "__main__":
    main()
