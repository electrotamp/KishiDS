"""Disassemble a range of the stock Kishi v2.70 image (base 0x08003000).

Usage: python tools/python-less: python tools/disasm_stock.py START END
START/END are image file offsets (hex ok).  Literal-pool loads are resolved
and bl/b targets are shown as image offsets.  Read-only; no USB access.
"""

from __future__ import annotations

import struct
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE / "python"))
from capstone import CS_ARCH_ARM, CS_MODE_THUMB, Cs  # noqa: E402

IMAGE = HERE.parent / "official-images" / "assets_legacy_02.70.bin"
BASE = 0x08003000

NAMES = {
    0x40021000: "RCC", 0x40022000: "FLASH", 0x40005C00: "USB", 0x40012400: "ADC",
    0x48000000: "GPIOA", 0x48000400: "GPIOB", 0x48000800: "GPIOC", 0x48000C00: "GPIOD",
    0x48001400: "GPIOF", 0x40010000: "SYSCFG", 0x40020000: "DMA", 0x40020008: "DMA_CH1",
    0x40000000: "TIM2", 0x40000400: "TIM3", 0x40014400: "TIM16", 0x40012C00: "TIM1",
}


def main() -> None:
    data = IMAGE.read_bytes()
    start = int(sys.argv[1], 16)
    end = int(sys.argv[2], 16)
    md = Cs(CS_ARCH_ARM, CS_MODE_THUMB)

    def lit(addr: int):
        off = addr - BASE
        return struct.unpack_from("<I", data, off)[0] if 0 <= off <= len(data) - 4 else None

    off = start
    while off < end:
        got = list(md.disasm(data[off : off + 4], BASE + off))
        if not got:
            print(f"{off:05x}  .hword {data[off]:02x}{data[off+1]:02x}")
            off += 2
            continue
        ins = got[0]
        note = ""
        if ins.mnemonic == "ldr" and "pc" in ins.op_str:
            imm = int(ins.op_str.split("#")[1].rstrip("]"), 16)
            val = lit(((ins.address + 4) & ~3) + imm)
            if val is not None:
                note = f"  ; = {val:#010x} {NAMES.get(val, '')}"
        elif ins.mnemonic in ("bl", "b", "beq", "bne", "bhs", "blo", "bhi", "bls", "bge", "blt", "bgt", "ble", "bmi", "bpl", "blx"):
            try:
                tgt = int(ins.op_str.lstrip("#"), 16) - BASE
                note = f"  ; -> off {tgt:#x}"
            except ValueError:
                pass
        raw = data[off : off + ins.size].hex()
        print(f"{off:05x}  {raw:8s} {ins.mnemonic} {ins.op_str}{note}")
        off += ins.size


if __name__ == "__main__":
    main()
