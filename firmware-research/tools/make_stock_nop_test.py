"""Make a one-byte, behavior-preserving executable-code test from stock v2.70.

This only writes a local experiment file; it never communicates with USB/DFU.
"""

from __future__ import annotations

import hashlib
from pathlib import Path


SOURCE = Path("firmware-research/device-backups/kishi-stock-v2.70-recovery.bin")
OUTPUT = Path("firmware-research/experiments/stock-v2.70-nop-code-test.bin")
SOURCE_SHA256 = "aa915363c858e5a06fb1ad15a82d059d50fd22a625d0663a3ce99fcab24f69be"
OFFSET = 0x16D9  # 00 BF (Thumb NOP) -> 00 46 (Thumb MOV r0, r0)


def main() -> None:
    source = bytearray(SOURCE.read_bytes())
    if hashlib.sha256(source).hexdigest() != SOURCE_SHA256:
        raise SystemExit("Refusing: source is not the verified v2.70 recovery image.")
    if bytes(source[OFFSET - 1 : OFFSET + 1]) != b"\x00\xbf":
        raise SystemExit("Refusing: expected Thumb NOP is not at the verified offset.")

    test = source[:]
    test[OFFSET] = 0x46
    differences = [index for index, (before, after) in enumerate(zip(source, test)) if before != after]
    if differences != [OFFSET] or len(test) != len(source):
        raise SystemExit("Refusing: output is not a one-byte, same-length mutation.")

    OUTPUT.parent.mkdir(exist_ok=True)
    OUTPUT.write_bytes(test)
    print(f"Source: {SOURCE} ({len(source)} bytes, SHA-256 {SOURCE_SHA256})")
    print(f"Output: {OUTPUT} ({len(test)} bytes, SHA-256 {hashlib.sha256(test).hexdigest()})")
    print(f"Only byte changed: 0x{OFFSET:04X}, NOP (00 BF) -> MOV r0, r0 (00 46)")
    print("Expected observable result if accepted: normal Kishi product name and unchanged behavior.")


if __name__ == "__main__":
    main()
