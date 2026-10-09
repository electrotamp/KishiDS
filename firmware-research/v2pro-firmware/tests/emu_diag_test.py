"""The diagnostic build's report path, on the emulated controller (uses emu_test.py's harness).

  ./build.sh diag && python tests/emu_diag_test.py

USB never gets configured in the emulator, so once SysTick says 3 s went by, kishi_v2pro_ds4_diag must write its
record (magic, stages reached, timed-out waits, register snapshot) to its record page (0x1E200) and then restart into
Razer's bootloader, exactly as on hardware, where tools/v2pro_dfu.py readdiag reads the page back.
"""

from __future__ import annotations

import struct
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import emu_test as emu  # noqa: E402
from unicorn import UC_HOOK_MEM_READ, UC_HOOK_MEM_WRITE  # noqa: E402
from unicorn.arm_const import UC_ARM_REG_LR, UC_ARM_REG_MSP, UC_ARM_REG_PC, UC_ARM_REG_SP  # noqa: E402

SYST_CVR, AIRCR, FLAG_ADDR = 0xE000E018, 0xE000ED0C, 0x20017FFC
RECORD = 0x1E200   # diag.c DIAG_RECORD_ADDR
failures = 0


def check(ok: bool, what: str) -> None:
    global failures
    print(("ok   " if ok else "FAIL ") + what)
    failures += not ok


def main() -> None:
    emu.BIN = emu.HERE / "kishi_v2pro_ds4_diag.bin"
    emu.ELF = emu.HERE / "kishi_v2pro_ds4_diag.elf"
    assert emu.BIN.exists(), "run ./build.sh diag first"
    emu.EXTRA_PAGES.add(RECORD)

    _, mu = emu.run(set())   # three main-loop passes: no report yet (SysTick has not moved)
    check(not any(c[0] in (4, 8, 12) for c in emu.FLASH["cmds"]), "no flash erase/write during normal start-up")

    ticks = [0x00FFFFFF]
    reads = [0]

    def systick(uc, access, addr, size, value, data):   # each read: 1/3 s at 48 MHz less on the down-counter
        reads[0] += 1
        ticks[0] = (ticks[0] - 16_000_000) & 0x00FFFFFF
        uc.mem_write(SYST_CVR, ticks[0].to_bytes(4, "little"))

    reset = []

    def aircr(uc, access, addr, size, value, data):
        if addr == AIRCR:
            reset.append(value)
            uc.emu_stop()

    mu.hook_add(UC_HOOK_MEM_READ, systick, begin=SYST_CVR, end=SYST_CVR + 3)
    mu.hook_add(UC_HOOK_MEM_WRITE, aircr)
    mu.emu_start(mu.reg_read(UC_ARM_REG_PC) | 1, 0, count=20_000_000)

    check(bool(reset) and reset[0] & 0xFFFF0004 == 0x05FA0004, "after ~3 s without USB it restarts into the bootloader")
    rec = bytes(mu.mem_read(RECORD, 512))
    dev, irqs = struct.unpack_from("<I", rec, 52)[0], struct.unpack_from("<I", rec, 16)[0]
    bits = (dev >> 16 & 1) | (dev >> 28 & 1) << 1 | (irqs > 0) << 2 | (dev >> 7 & 1) << 3
    want_reads = 9 + 4.5 * (1 + bits)   # 3 s to the report, then 1.5 s per unit, at 1/3 s per SysTick read
    check(abs(reads[0] - want_reads) <= 2,
          f"  ...after the timing-channel wait for USB bits {bits:04b} ({reads[0]} SysTick reads, ~{want_reads:.0f} expected)")
    flag = int.from_bytes(mu.mem_read(FLAG_ADDR, 4), "little")
    check(flag == 0xAAAAAAAA, f"  ...with Razer's boot flag: {flag:#x}")
    page = bytes(mu.mem_read(RECORD, 512))
    check(page[:8] == b"KDSDIAG1", f"record written to {RECORD:#x}: {page[:8]!r}")
    check(emu.FLASH["state"] == "programmed", "  ...and the saved-settings page left alone")
    stages, timeouts, irqs, loops = struct.unpack_from("<IIII", page, 8)
    want = sum(1 << s for s in range(7))   # DS_RESET .. DS_LOOP
    check(stages & want == want, f"  stages reset..main loop recorded: {stages:#x}")
    check(timeouts == 0, f"  no wait timed out in the emulator: {timeouts:#x}")
    check(loops >= 3, f"  main loop passes counted: {loops}")
    vector = struct.unpack_from("<I", page, 28)[0]
    check(vector == 0, f"  reported as the USB timeout, not a fault (vector {vector})")
    regs = struct.unpack_from("<32I", page, 52)
    check(regs[25] == 0x8000, f"  register snapshot taken (VTOR {regs[25]:#x})")   # diag_regs order in diag.c
    check(regs[23] & (1 << 16) != 0, f"  USBFSH PORTMODE read with its clock on: {regs[23]:#x}")
    check(not emu.UNCLOCKED, f"  no unclocked peripheral touched while reporting {emu.UNCLOCKED}")

    # A fault: Default_Handler with a stacked frame on MSP (EXC_RETURN 0xFFFFFFF9) records vector, PC and LR in RAM and
    # goes straight to the timing channel (20 + last DIAG_STEP), touching no peripheral (no snapshot, no flash).
    _, mu = emu.run(set())
    crumb = mu.mem_read(emu.symbol("diag_breadcrumb"), 1)[0]
    reads[0] = 0
    touched: list[int] = []
    mu.hook_add(UC_HOOK_MEM_READ | UC_HOOK_MEM_WRITE,
                lambda uc, access, addr, size, value, data: touched.append(addr), begin=0x40000000, end=0x4FFFFFFF)
    frame_at = 0x04007000
    mu.mem_write(frame_at, struct.pack("<8I", 0, 0, 0, 0, 0, 0x8123, 0x9ABC, 0x01000000))   # r0-r3 r12 lr pc xpsr
    mu.mem_write(0xE000ED04, (3).to_bytes(4, "little"))      # ICSR.VECTACTIVE = HardFault
    mu.mem_write(0xE000ED28, (0x8200).to_bytes(4, "little"))  # CFSR: precise bus error, BFAR valid
    mu.reg_write(UC_ARM_REG_MSP, frame_at)
    mu.reg_write(UC_ARM_REG_SP, frame_at)
    mu.reg_write(UC_ARM_REG_LR, 0xFFFFFFF9)
    reset.clear()
    mu.hook_add(UC_HOOK_MEM_WRITE, aircr)
    mu.hook_add(UC_HOOK_MEM_READ, systick, begin=SYST_CVR, end=SYST_CVR + 3)   # the timing-channel wait reads it
    mu.emu_start(emu.symbol("Default_Handler") | 1, 0, count=20_000_000)
    d = emu.symbol("diag")
    vector, cfsr, _, _, pc, lr = struct.unpack_from("<6I", bytes(mu.mem_read(d + 28, 24)))
    check(bool(reset) and (vector, cfsr, pc, lr) == (3, 0x8200, 0x9ABC, 0x8123),
          f"fault: vector {vector}, CFSR {cfsr:#x}, PC {pc:#x}, LR {lr:#x} kept in RAM, then the bootloader")
    want_reads = 4.5 * (20 + crumb)
    check(abs(reads[0] - want_reads) <= 2, f"  ...after the timing-channel wait for DIAG_STEP({crumb}) ({reads[0]} SysTick "
          f"reads, ~{want_reads:.0f} expected)")
    touched = [a for a in touched if not 0x40000000 <= a < 0x40001000]   # SYSCON (always clocked): the SRAM clocks
    check(not touched, f"  ...touching no peripheral but SYSCON from the fault handler {[hex(a) for a in touched[:4]]}")
    check(not emu.STALE_ALL, f"every flash command issued with DONE cleared {emu.STALE_ALL[:3]}")

    print(f"{failures} failure(s)")
    sys.exit(1 if failures else 0)


if __name__ == "__main__":
    main()
