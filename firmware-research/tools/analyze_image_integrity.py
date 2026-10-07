"""Offline checks for common checksum fields in a raw Kishi firmware image.

This only reads an image file.  It does not interact with USB or DFU devices.
"""

from __future__ import annotations

import argparse
import binascii
import struct
from pathlib import Path


def stm32_crc32(data: bytes, initial: int = 0xFFFFFFFF) -> int:
    """STM32 CRC peripheral default: CRC-32/MPEG-2, padded with erased bytes."""
    crc = initial
    padded = data + b"\xff" * ((-len(data)) % 4)
    for (word,) in struct.iter_unpack(">I", padded):
        crc ^= word
        for _ in range(32):
            crc = ((crc << 1) ^ 0x04C11DB7) & 0xFFFFFFFF if crc & 0x80000000 else (crc << 1) & 0xFFFFFFFF
    return crc


def representations(value: int) -> set[bytes]:
    return {value.to_bytes(4, "little"), value.to_bytes(4, "big")}


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("image", type=Path)
    args = parser.parse_args()
    data = args.image.read_bytes()
    print(f"Image: {args.image}\nLength: {len(data)} bytes")

    candidates = {
        "CRC-32/ISO-HDLC (all bytes)": binascii.crc32(data) & 0xFFFFFFFF,
        "CRC-32/ISO-HDLC (excluding final word)": binascii.crc32(data[:-4]) & 0xFFFFFFFF,
        "STM32 CRC-32/MPEG-2 (all bytes)": stm32_crc32(data),
        "STM32 CRC-32/MPEG-2 (excluding final word)": stm32_crc32(data[:-4]),
    }
    print("\nWhole-image candidates:")
    for name, value in candidates.items():
        print(f"  {name}: 0x{value:08X}")

    print("\nCandidate stored as final four bytes:")
    final = data[-4:]
    print(f"  final bytes: {final.hex(' ')}")
    for name, value in candidates.items():
        print(f"  {name}: {'yes' if final in representations(value) else 'no'}")

    print("\nPrefix checks (checksum immediately after the covered bytes):")
    found = []
    iso_crc = 0
    stm_crc = 0xFFFFFFFF
    for offset in range(4, len(data) - 4, 4):
        block = data[offset - 4 : offset]
        iso_crc = binascii.crc32(block, iso_crc) & 0xFFFFFFFF
        stm_crc = stm32_crc32(block, stm_crc)
        stored = data[offset : offset + 4]
        for name, value in (("CRC-32/ISO-HDLC", iso_crc), ("STM32 CRC-32/MPEG-2", stm_crc)):
            if stored in representations(value):
                found.append((offset, name, value, stored))
    if not found:
        print("  none found")
    else:
        for offset, name, value, stored in found:
            print(f"  offset 0x{offset:04X}: {name} = 0x{value:08X} ({stored.hex(' ')})")


if __name__ == "__main__":
    main()
