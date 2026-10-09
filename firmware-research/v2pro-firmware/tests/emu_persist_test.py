"""Settings persistence on the emulated controller (uses emu_test.py's harness and flash controller model).

  python tests/emu_persist_test.py      (after ./build.sh; needs unicorn)

Scenarios: fresh image, save then reboot, erase, power cut after the erase, power cut mid-program, and the address
guards.  The flash model makes any bus read of an erased or half-written page fail the test, since on the LPC55 that
read faults and would do so on every boot.
"""

from __future__ import annotations

import os
import struct
import sys
import zlib
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import emu_test as emu  # noqa: E402
from unicorn.arm_const import UC_ARM_REG_LR, UC_ARM_REG_R0, UC_ARM_REG_R1, UC_ARM_REG_SP  # noqa: E402

RETURN = 0x7FFE            # in mapped flash below the app: where called functions "return" to
SCRATCH = 0x2000F000       # RAM for the record we hand to hw_flash_save
COMMIT = 0x5AFE5AFE
failures = 0


def check(ok: bool, what: str) -> None:
    global failures
    print(("ok   " if ok else "FAIL ") + what)
    failures += not ok


def call(mu, name: str, *args: int) -> int:
    for reg, value in zip((UC_ARM_REG_R0, UC_ARM_REG_R1), args):
        mu.reg_write(reg, value)
    mu.reg_write(UC_ARM_REG_SP, 0x04006000)
    mu.reg_write(UC_ARM_REG_LR, RETURN | 1)
    mu.emu_start(emu.symbol(name) | 1, RETURN, count=5_000_000)
    return mu.reg_read(UC_ARM_REG_R0)


def u8(mu, name: str) -> int:
    return mu.mem_read(emu.symbol(name), 1)[0]


def record(mu, left_dead: int) -> bytes:
    """A valid saved record: the active config with one field changed, its CRC, the image id and the commit word."""
    cfg = bytearray(mu.mem_read(emu.symbol("kcfg"), 256))
    cfg[33] = left_dead
    struct.pack_into("<I", cfg, 12, zlib.crc32(bytes(cfg[16:256])))
    image_id = struct.unpack("<I", mu.mem_read(emu.symbol("kcfg_image_id"), 4))[0]
    return bytes(cfg) + struct.pack("<II", image_id, COMMIT)


def main() -> None:
    blank = b"\xff" * 512

    _, mu = emu.run(set(), page=blank)
    check(u8(mu, "kcfg_from_saved") == 0, "fresh image: no saved record, built-in settings")
    default_dead = mu.mem_read(emu.symbol("kcfg") + 33, 1)[0]

    rec = record(mu, 15)
    check(rec[:8] == b"KISHICFG", "the active configuration carries the block magic (a save would be accepted)")
    mu.mem_write(SCRATCH, rec)
    first = len(emu.FLASH["cmds"])
    r = call(mu, "hw_flash_save", SCRATCH)
    page = bytes(mu.mem_read(emu.SAVED_PAGE, 512))
    cmds = [c for c, _ in emu.FLASH["cmds"][first:]]
    check(r == 0 and page == rec + b"\xff" * (512 - len(rec)), f"save: page holds the record (returned {r:#x})")
    check(cmds.count(4) == 1 and cmds.count(8) == 32 and cmds.count(12) == 1 and cmds.count(3) == 32,
          f"save: 1 erase, 32 word writes, 1 program, 32 read-backs ({len(cmds)} commands)")

    _, mu = emu.run(set(), page=page)
    dead = mu.mem_read(emu.symbol("kcfg") + 33, 1)[0]
    check(u8(mu, "kcfg_from_saved") == 1 and dead == 15,
          f"reboot: saved record in use (from_saved {u8(mu, 'kcfg_from_saved')}, left deadzone {default_dead} -> {dead})")

    r = call(mu, "hw_flash_erase")
    page = bytes(mu.mem_read(emu.SAVED_PAGE, 512))
    check(r == 0 and page == blank and emu.FLASH["state"] == "programmed", "erase: page programmed back to 0xFF")
    _, mu = emu.run(set(), page=page)
    check(u8(mu, "kcfg_from_saved") == 0, "reboot after erase: built-in settings")

    for state, label in (("erased", "power cut after the erase"), ("torn", "power cut while programming")):
        try:
            _, mu = emu.run(set(), page=os.urandom(512) if state == "torn" else blank, page_state=state)
            check(u8(mu, "kcfg_from_saved") == 0, f"{label}: boots, no bus read of the page, built-in settings")
        except AssertionError as exc:
            check(False, f"{label}: {exc}")

    _, mu = emu.run(set(), page=blank)
    before = len(emu.FLASH["cmds"])
    for addr in (0x7E00, 0x1EC00, 0x1E100):   # bootloader, past Razer's image span, not page-aligned
        r = call(mu, "flash_erase_page", addr)
        check(r == 0xFFFFFFFF, f"flash_erase_page({addr:#x}) refused")
    r = call(mu, "flash_program_page", 0x7E00, SCRATCH)
    check(r == 0xFFFFFFFF and len(emu.FLASH["cmds"]) == before, "flash_program_page(0x7e00) refused, no command issued")
    check(not emu.STALE_ALL, f"every flash command issued with DONE cleared {emu.STALE_ALL[:3]}")

    print(f"{failures} failure(s)")
    sys.exit(1 if failures else 0)


if __name__ == "__main__":
    main()
