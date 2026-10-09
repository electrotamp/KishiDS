"""The RGB LED on the emulated controller (uses emu_test.py's harness): SCTimer set-up as Razer's, pins, behaviour.

  python tests/emu_led_test.py      (after ./build.sh; needs unicorn)

Checks the PWM set-up (one counter limited at 0xFFFF, event 0 sets outputs 0-2, event n clears output n-1, conflicts
clear), the pins' SCT functions, that the LED is dark until the host configures the device (led_mode 3, the V2 Pro
default), and that a DS4 lightbar colour from the host (output report 0x05, OUT endpoint and SET_REPORT) reaches the
three channels.
"""

from __future__ import annotations

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import emu_test as emu  # noqa: E402
from emu_persist_test import RETURN, SCRATCH  # noqa: E402
from unicorn.arm_const import (UC_ARM_REG_LR, UC_ARM_REG_R0, UC_ARM_REG_R1, UC_ARM_REG_R2, UC_ARM_REG_R3,  # noqa: E402
                               UC_ARM_REG_SP, UC_ARM_REG_XPSR)

SCT = 0x40085000
failures = 0


def call(mu, name: str, *args: int) -> None:
    """AAPCS call with up to 4 register arguments and the rest on the stack."""
    sp = 0x04006000
    for reg, value in zip((UC_ARM_REG_R0, UC_ARM_REG_R1, UC_ARM_REG_R2, UC_ARM_REG_R3), args):
        mu.reg_write(reg, value)
    stacked = args[4:]
    if stacked:
        sp -= 8 * ((len(stacked) + 1) // 2)   # keep 8-byte alignment
        for i, value in enumerate(stacked):
            mu.mem_write(sp + 4 * i, value.to_bytes(4, "little"))
    mu.reg_write(UC_ARM_REG_SP, sp)
    mu.reg_write(UC_ARM_REG_LR, RETURN | 1)
    # emu.run() stops from a hook, possibly inside an IT block: clear the stale IT state (keep only the Thumb bit),
    # or the first instructions of the called function run as conditional on garbage.
    mu.reg_write(UC_ARM_REG_XPSR, 1 << 24)
    mu.emu_start(emu.symbol(name) | 1, RETURN, count=5_000_000)


def check(ok: bool, what: str) -> None:
    global failures
    print(("ok   " if ok else "FAIL ") + what)
    failures += not ok


def main() -> None:
    _, mu = emu.run(set())
    word = lambda a: int.from_bytes(mu.mem_read(a, 4), "little")
    matchrel = lambda: [word(SCT + 0x200 + 4 * n) for n in (1, 2, 3)]

    check(word(SCT) & (1 << 17) != 0 and word(SCT + 0x200) == 0xFFFF,
          f"one counter, auto-limited at match 0 = 0xFFFF (CONFIG {word(SCT):#x})")
    check([word(SCT + 0x304 + 8 * n) for n in range(4)] == [0x1000, 0x1001, 0x1002, 0x1003],
          "events 0-3 on matches 0-3, as Razer's")
    sets = [word(SCT + 0x500 + 8 * n) for n in range(3)]
    clrs = [word(SCT + 0x504 + 8 * n) for n in range(3)]
    check(sets == [1, 1, 1] and clrs == [2, 4, 8], f"event 0 sets outputs 0-2, events 1-3 clear them ({sets}, {clrs})")
    check(word(SCT + 0x58) == 0x2A and word(SCT + 4) & 4 == 0, "conflicts clear the output; counter running")
    pins = {(0, 2): 3, (0, 3): 3, (1, 25): 2}
    bad = [p for p, f in pins.items() if word(emu.IOCON + 0x80 * p[0] + 4 * p[1]) & 0x7 != f]
    check(not bad, f"P0_2, P0_3, P1_25 on their SCT0_OUT functions {bad or ''}")
    check(matchrel() == [0, 0, 0], f"dark while the host has not configured the device: {matchrel()}")

    # Configured, default colour (blue, full brightness).
    call(mu, "led_update", 1000, 1)
    check(matchrel() == [0, 0, 0xFFFE], f"configured: default blue at full brightness {matchrel()}")

    # Lightbar from the OUT endpoint: report ID in byte 0, flags 0x02 (lightbar), R G B at 6..8.
    pkt = bytes([0x05, 0x02, 0x04, 0, 0, 0, 255, 128, 0]) + bytes(23)
    mu.mem_write(SCRATCH, pkt)
    call(mu, "tud_hid_set_report_cb", 0, 0, 2, SCRATCH, len(pkt))   # instance, report_id 0, OUTPUT, buffer, len
    call(mu, "led_update", 1000, 1)
    m = matchrel()   # the colour is linear, only the brightness goes through gamma
    check(m == [0xFFFE, 128 * 257, 0], f"host colour (255, 128, 0) reaches the PWM: {m}")

    # Same through SET_REPORT (ID passed separately, stripped from the buffer), with rumble too.
    pkt = bytes([0x03, 0x04, 0, 40, 200, 0, 0, 255]) + bytes(23)
    mu.mem_write(SCRATCH, pkt)
    call(mu, "tud_hid_set_report_cb", 0, 0x05, 2, SCRATCH, len(pkt))
    call(mu, "led_update", 1000, 1)
    weak, strong = mu.mem_read(emu.symbol("rumble_weak"), 1)[0], mu.mem_read(emu.symbol("rumble_strong"), 1)[0]
    check(matchrel() == [0, 0, 0xFFFE] and (weak, strong) == (40, 200),
          f"SET_REPORT: colour (0, 0, 255) and rumble weak {weak} / strong {strong} recorded")

    call(mu, "led_update", 1000, 0)
    check(matchrel() == [0, 0, 0], "dark again once the host deconfigures the device")

    # KishiDS colour (config led_rgb at 53, led_fixed at 192): shown as soon as it changes, then a host may replace it...
    kcfg = emu.symbol("kcfg")
    host = lambda rgb: (mu.mem_write(SCRATCH, bytes([0x05, 0x02, 0x04, 0, 0, 0, *rgb]) + bytes(23)),
                        call(mu, "tud_hid_set_report_cb", 0, 0, 2, SCRATCH, 32))
    mu.mem_write(kcfg + 53, bytes([0, 255, 0]))
    call(mu, "led_update", 1000, 1)
    check(matchrel() == [0, 0xFFFE, 0], f"KishiDS colour (0, 255, 0) shown once set: {matchrel()}")
    host((255, 0, 0))
    call(mu, "led_update", 1000, 1)
    check(matchrel() == [0xFFFE, 0, 0], f"...a host's lightbar colour replaces it (led_fixed 0): {matchrel()}")
    # ...unless led_fixed keeps it.
    mu.mem_write(kcfg + 192, bytes([1]))
    host((0, 0, 0))
    call(mu, "led_update", 1000, 1)
    check(matchrel() == [0, 0xFFFE, 0], f"led_fixed 1: the host's black is ignored, KishiDS colour kept: {matchrel()}")

    print(f"{failures} failure(s)")
    sys.exit(1 if failures else 0)


if __name__ == "__main__":
    main()
