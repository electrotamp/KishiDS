"""The three ways back into Razer's bootloader, on the emulated controller (uses emu_test.py's harness).

  python tests/emu_bootloader_test.py      (after ./build.sh; needs unicorn)

Each must do what Razer's firmware 2.2 does on SetDeviceMode [0x01, 0x00]: SRAM1-4 clocks on, 0xAAAAAAAA written to
0x20017FFC, then SYSRESETREQ (see bootloader.h).  Paths: View + Menu held at power-on (before the clock switch and
USB), any fault or unused interrupt (Default_Handler), and KishiDS live command 7.  Also checks that a normal boot,
and a boot with only one of the two buttons held, leave the flag alone.
"""

from __future__ import annotations

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import emu_test as emu  # noqa: E402
from emu_persist_test import SCRATCH, call  # noqa: E402
from unicorn import UC_ARCH_ARM, UC_HOOK_CODE, UC_HOOK_MEM_WRITE, UC_MODE_MCLASS, UC_MODE_THUMB, Uc  # noqa: E402
from unicorn.arm_const import UC_ARM_REG_SP  # noqa: E402

FLAG_ADDR, FLAG_ENTER = 0x20017FFC, 0xAAAAAAAA
AIRCR, SYSRESETREQ = 0xE000ED0C, 0x05FA0004
AHBCLKCTRLSET0, MAINCLKSELA = emu.SYSCON + 0x220, emu.SYSCON + 0x280
SRAM1_4 = 0x78
failures = 0


def check(ok: bool, what: str) -> None:
    global failures
    print(("ok   " if ok else "FAIL ") + what)
    failures += not ok


def watch(mu) -> list[tuple[int, int]]:
    """Record writes to the flag word and AIRCR (in order, with the SYSCON writes), and stop at the reset request."""
    events: list[tuple[int, int]] = []

    def hook(uc, access, addr, size, value, data):
        if addr == FLAG_ADDR or addr == AIRCR or addr in (AHBCLKCTRLSET0, MAINCLKSELA):
            events.append((addr, value & 0xFFFFFFFF))
        if addr == AIRCR and value & 0xFFFF0004 == SYSRESETREQ:
            uc.emu_stop()

    mu.hook_add(UC_HOOK_MEM_WRITE, hook)
    return events


def entered(events: list[tuple[int, int]]) -> str | None:
    """None when the sequence is Razer's (SRAM clocks, flag, reset), else what is wrong."""
    addrs = [a for a, _ in events]
    if FLAG_ADDR not in addrs or AIRCR not in addrs:
        return f"no flag/reset written: {[(hex(a), hex(v)) for a, v in events]}"
    flag = addrs.index(FLAG_ADDR)
    if events[flag][1] != FLAG_ENTER:
        return f"flag {events[flag][1]:#x}"
    if not any(a == AHBCLKCTRLSET0 and v & SRAM1_4 == SRAM1_4 for a, v in events[:flag]):
        return "SRAM1-4 clocks not enabled before the flag write"
    if addrs.index(AIRCR) < flag:
        return "reset requested before the flag write"
    return None


def boot_with(pressed: set[str]) -> tuple[list[tuple[int, int]], bool]:
    """Boot from reset with these buttons held, without the ADC/flash models (the combo check runs before them);
    returns (events, reached the main loop)."""
    image = emu.BIN.read_bytes()
    mu = Uc(UC_ARCH_ARM, UC_MODE_THUMB | UC_MODE_MCLASS)
    mu.mem_map(0x0, 0xA0000)
    mu.mem_write(0x8000, image)
    for base, size in ((0x20000000, 0x40000), (0x04000000, 0x10000), (0x40000000, 0x104000), (0xE0000000, 0x100000)):
        mu.mem_map(base, size)
    for name, (port, pin) in emu.BUTTONS.items():
        mu.mem_write(emu.GPIO + 0x20 * port + pin, b"\x00" if name in pressed else b"\x01")
    mu.mem_write(emu.ADC + 0x14, (1 << 10).to_bytes(4, "little"))
    mu.hook_add(UC_HOOK_MEM_WRITE, emu.model_syscon)
    events = watch(mu)
    reached = [False]
    loop = emu.after_report_build()

    def at_loop(uc, addr, size, data):
        reached[0] = True
        uc.emu_stop()

    mu.hook_add(UC_HOOK_CODE, at_loop, begin=loop, end=loop)
    mu.reg_write(UC_ARM_REG_SP, int.from_bytes(image[0:4], "little"))
    mu.emu_start(int.from_bytes(image[4:8], "little"), 0, count=3_000_000)
    return events, reached[0]


def main() -> None:
    assert emu.BIN.exists(), "run ./build.sh first"

    events, reached = boot_with({"VIEW", "MENU"})
    problem = entered(events)
    check(problem is None and not reached, f"View + Menu at power-on: Razer's bootloader entry {problem or ''}")
    check(MAINCLKSELA not in [a for a, _ in events], "  ...before the clock switch (on the reset clock, no USB set-up)")

    for held in (set(), {"VIEW"}, {"MENU"}, {"VIEW", "HOME"}):
        _, mu = emu.run(held)   # asserts the main loop ran
        word = lambda a: int.from_bytes(mu.mem_read(a, 4), "little")
        check(word(FLAG_ADDR) == 0 and word(AIRCR) == 0,
              f"boot with {sorted(held) or 'nothing'} held: main loop runs, flag and AIRCR untouched")

    image = emu.BIN.read_bytes()
    vectors = [int.from_bytes(image[4 * i:4 * i + 4], "little") for i in range(16 + 60)]
    default = emu.symbol("Default_Handler") | 1
    others = {i for i, v in enumerate(vectors) if v and v != default}
    check(others == {0, 1, 16 + 28}, f"every fault/interrupt vector except SP, reset and USB0 is Default_Handler {sorted(others)}")

    _, mu = emu.run(set())
    events = watch(mu)
    call(mu, "Default_Handler")
    problem = entered(events)
    check(problem is None, f"Default_Handler (faults): Razer's bootloader entry {problem or ''}")

    _, mu = emu.run(set())
    events = watch(mu)
    mu.mem_write(SCRATCH, bytes([0xAC, 0x4B, 7, 0]) + bytes(60))
    call(mu, "live_set_report", SCRATCH, 64)
    check(not events, "live command 7 is deferred (nothing written from the USB handler)")
    call(mu, "live_service")
    problem = entered(events)
    check(problem is None, f"live command 7 then live_service: Razer's bootloader entry {problem or ''}")

    _, mu = emu.run(set())
    events = watch(mu)
    mu.mem_write(SCRATCH, bytes([0xAC, 0x4B, 6, 0]) + bytes(60))
    call(mu, "live_set_report", SCRATCH, 64)
    call(mu, "live_service")
    check(AIRCR in [a for a, _ in events] and FLAG_ADDR not in [a for a, _ in events],
          "live command 6 (plain reboot) resets without touching the flag")

    print(f"{failures} failure(s)")
    sys.exit(1 if failures else 0)


if __name__ == "__main__":
    main()
