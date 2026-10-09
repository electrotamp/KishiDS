"""Rumble on the emulated controller: two DRV2605s (0x5A) on FLEXCOMM1 and FLEXCOMM4 (uses emu_test.py's harness).

  python tests/emu_haptics_test.py      (after ./build.sh; needs unicorn)

A model of the LPC55 FLEXCOMM I2C master (STAT / MSTCTL / MSTDAT state machine) with a DRV2605 behind each bus.
Checks: nothing is sent before 250 ms; then each driver gets Razer's sequence (STATUS read, standby, the 10-register
table, RTP mode, RTP 0); a DS4 rumble level then lands in RTP (0..255 -> 0..127), only when it changes; a bus with no
device answering is reported (0xFF) and never written again.
"""

from __future__ import annotations

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import emu_test as emu  # noqa: E402
from emu_led_test import call  # noqa: E402
from unicorn import UC_HOOK_MEM_READ, UC_HOOK_MEM_WRITE  # noqa: E402

FC = {1: 0x40087000, 4: 0x4008A000}
RAZER_TABLE = [(0x01, 0x00), (0x02, 0x00), (0x17, 0x89), (0x1D, 0x80), (0x16, 0x4C), (0x18, 0x0C), (0x19, 0x6C),
               (0x1A, 0xA4), (0x1B, 0x9A), (0x1C, 0xF5)]
failures = 0


def check(ok: bool, what: str) -> None:
    global failures
    print(("ok   " if ok else "FAIL ") + what)
    failures += not ok


class Bus:
    """FLEXCOMM I2C master + one DRV2605 at 0x5A (or nothing, if present is False)."""

    def __init__(self, present: bool = True):
        self.present = present
        self.state = 0          # MSTSTATE: 0 idle, 1 rx ready, 2 tx ready, 3 nack address, 4 nack data
        self.dat = 0
        self.cur: list[int] | None = None
        self.reading = False
        self.reg_ptr = 0
        self.regs = bytearray(0x23)
        self.regs[0] = 0xE0     # STATUS: device ID 7 (DRV2605L)
        self.log: list[tuple] = []   # ("w", reg, value) / ("r", reg)

    def control(self, value: int) -> None:
        if value & 2:            # START (or repeated START)
            if self.cur and not self.reading and len(self.cur) >= 1 and self.dat & 1:
                self.reading = True                      # repeated start for a read
            else:
                self.cur, self.reading = [], False
            addr = self.dat >> 1
            if addr != 0x5A or not self.present:
                self.state = 3
                return
            if self.dat & 1:
                self.log.append(("r", self.reg_ptr))
                self.dat = self.regs[self.reg_ptr]
                self.state = 1
            else:
                self.state = 2
        elif value & 4:          # STOP
            if self.cur is not None and not self.reading and len(self.cur) == 2:
                reg, v = self.cur
                self.regs[reg] = v
                self.log.append(("w", reg, v))
            self.cur, self.reading, self.state = None, False, 0
        elif value & 1:          # CONTINUE: the byte in MSTDAT goes out
            if self.cur is not None:
                self.cur.append(self.dat & 0xFF)
                if len(self.cur) == 1:
                    self.reg_ptr = self.cur[0]
            self.state = 2


def main() -> None:
    buses = {1: Bus(), 4: Bus()}
    _, mu = emu.run(set())

    def on_write(uc, access, addr, size, value, data):
        for n, base in FC.items():
            if addr == base + 0x828:
                buses[n].dat = value
            elif addr == base + 0x820:
                buses[n].control(value)

    def on_read(uc, access, addr, size, value, data):
        for n, base in FC.items():
            if addr == base + 0x804:     # STAT: always pending, current state
                uc.mem_write(addr, (1 | (buses[n].state << 1)).to_bytes(4, "little"))
            elif addr == base + 0x828:
                uc.mem_write(addr, buses[n].dat.to_bytes(4, "little"))

    mu.hook_add(UC_HOOK_MEM_WRITE, on_write)
    mu.hook_add(UC_HOOK_MEM_READ, on_read)

    call(mu, "haptics_update", 100, 0, 0)
    check(not buses[1].log and not buses[4].log, "nothing sent to the drivers before 250 ms")

    call(mu, "haptics_update", 300, 0, 0)
    want = [("r", 0x00), ("w", 0x01, 0x40)] + [("w", r, v) for r, v in RAZER_TABLE] + [("w", 0x01, 0x05), ("w", 0x02, 0)]
    for n in (1, 4):
        check(buses[n].log == want, f"FLEXCOMM{n}: Razer's DRV2605 sequence (STATUS, standby, table, RTP mode) "
              f"{'' if buses[n].log == want else buses[n].log}")
    word = lambda a: int.from_bytes(mu.mem_read(a, 4), "little")
    check(word(FC[1] + 0xFF8) == 3 and word(FC[4] + 0xFF8) == 3, "both FLEXCOMMs in I2C mode")
    check(word(0x400002B4) == 2 and word(0x400002C0) == 2, "their clocks on FRO 12 MHz (FCCLKSEL1/4 = 2)")
    iocon = lambda p, n: word(emu.IOCON + 0x80 * p + 4 * n)
    check([iocon(0, 13), iocon(0, 14), iocon(1, 20), iocon(1, 21)] == [0x101, 0x101, 0x105, 0x105],
          "I2C pins as Razer's BOARD_InitPins (P0_13/P0_14 0x101, P1_20/P1_21 0x105)")
    status = bytes(mu.mem_read(emu.symbol("haptics_status"), 2))
    check(status == b"\xe0\xe0", f"STATUS of both drivers kept for telemetry: {status.hex(' ')}")

    for b in buses.values():
        b.log.clear()
    call(mu, "haptics_update", 310, 255, 128)
    rtp = lambda level: level * 127 * 80 // (255 * 100)   # 80 % of full scale at level 255 (haptics_v2pro.c)
    check(buses[1].log == [("w", 0x02, rtp(255))] and buses[4].log == [("w", 0x02, rtp(128))],
          f"rumble strong 255 / weak 128 -> RTP {rtp(255)} / {rtp(128)} ({buses[1].log}, {buses[4].log})")
    for b in buses.values():
        b.log.clear()
    call(mu, "haptics_update", 320, 255, 128)
    check(not buses[1].log and not buses[4].log, "same levels: nothing written")
    call(mu, "haptics_update", 330, 0, 0)
    check(buses[1].log == [("w", 0x02, 0)] and buses[4].log == [("w", 0x02, 0)], "back to 0: both stop")

    # The KishiDS rumble_level field (config offset 52) replaces the 80 % default; 0 keeps the default.
    kcfg = emu.symbol("kcfg")
    for pct in (50, 100):
        mu.mem_write(kcfg + 52, bytes([pct]))
        for b in buses.values():
            b.log.clear()
        call(mu, "haptics_update", 340 + pct, 255, 255)
        want_rtp = 255 * 127 * pct // (255 * 100)
        check(buses[1].log == [("w", 0x02, want_rtp)] and buses[4].log == [("w", 0x02, want_rtp)],
              f"rumble_level {pct} %: level 255 -> RTP {want_rtp} ({buses[1].log})")
    mu.mem_write(kcfg + 52, bytes([0]))
    for b in buses.values():
        b.log.clear()
    call(mu, "haptics_update", 500, 255, 255)
    check(buses[1].log == [("w", 0x02, rtp(255))], f"rumble_level 0: back to the 80 % default ({buses[1].log})")

    # A driver that does not answer: reported, then left alone.
    buses = {1: Bus(present=False), 4: Bus()}
    _, mu = emu.run(set())
    mu.hook_add(UC_HOOK_MEM_WRITE, on_write)
    mu.hook_add(UC_HOOK_MEM_READ, on_read)
    call(mu, "haptics_update", 300, 200, 200)
    status = bytes(mu.mem_read(emu.symbol("haptics_status"), 2))
    check(status[0] == 0xFF and not buses[1].log and buses[4].log[-1] == ("w", 0x02, 200 * 127 * 80 // 25500),
          f"no answer on FLEXCOMM1: status 0xFF, never written; FLEXCOMM4 still driven ({status.hex(' ')})")

    print(f"{failures} failure(s)")
    sys.exit(1 if failures else 0)


if __name__ == "__main__":
    main()
