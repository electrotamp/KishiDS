"""Run the built kishi_v2pro_ds4.bin in the Unicorn emulator (no hardware) and check the skeleton end to end.

  python tests/emu_test.py      (after ./build.sh; needs unicorn, see ../../requirements.txt)

Checks: start-up turns on the clocks Razer's firmware turns on; every button pin gets the pull-up input configuration and
every analog pin the analog configuration; with nothing pressed the DS4 report is neutral; each mapped button changes
the report; M1/M2 click the touchpad's left/right half.
"""

from __future__ import annotations

import re
import subprocess
import sys
from pathlib import Path

from unicorn import (UC_ARCH_ARM, UC_HOOK_CODE, UC_HOOK_MEM_READ, UC_HOOK_MEM_WRITE, UC_MEM_WRITE, UC_MODE_MCLASS,
                     UC_MODE_THUMB, Uc)
from unicorn.arm_const import UC_ARM_REG_PC, UC_ARM_REG_PRIMASK, UC_ARM_REG_SP

HERE = Path(__file__).resolve().parents[1]
BIN = HERE / "kishi_v2pro_ds4.bin"
ELF = HERE / "kishi_v2pro_ds4.elf"

# Must match board_v2pro.c (pins from ../BOARD_MAP_V2PRO.md).
BUTTONS = {
    "A": (0, 4), "B": (0, 6), "X": (0, 8), "Y": (0, 9), "UP": (1, 7), "DOWN": (0, 24), "LEFT": (1, 17), "RIGHT": (0, 22),
    "L1": (0, 0), "R1": (0, 17), "L3": (1, 14), "R3": (0, 18), "M1": (0, 27), "M2": (0, 31), "VIEW": (1, 26),
    "MENU": (0, 19), "HOME": (1, 6), "SHARE": (1, 5),
}
UNMAPPED: set[str] = set()
ANALOG = [(0, 10), (0, 15), (0, 16), (0, 23), (1, 0), (1, 8)]

GPIO, IOCON, SYSCON = 0x4008C000, 0x40001000, 0x40000000
VTOR, SYST_CSR, NVIC_ICER0 = 0xE000ED08, 0xE000E010, 0xE000E180
FLASH_INT_STATUS = 0x40034FE0
ADC = 0x400A0000
WRITES: list[tuple[int, int]] = []

# Analog pins -> (ADC channel, B side), and which shared raw[] slot board_v2pro.c gives them (RX RY R2 LY LX L2).
ADC_PINS = {"P0_16": (0, 1), "P0_23": (0, 0), "P0_15": (2, 0), "P1_0": (3, 1), "P1_8": (4, 0), "P0_10": (1, 0)}
SLOT = {"P0_16": "RX", "P0_23": "RY", "P0_15": "R2", "P1_0": "LY", "P1_8": "LX", "P0_10": "L2"}
# Pin voltages (12-bit) measured on a controller (../device-backups/phase5_raw.txt): at rest, and moved right on X,
# up on RY, down on LY (LY falls going up), fully pressed on the triggers.  board_defaults.h is calibrated from them.
REST = {"RX": 2237, "RY": 2012, "LX": 1947, "LY": 2001, "L2": 1640, "R2": 1735}
MOVED = {"RX": 3489, "RY": 3334, "LX": 3265, "LY": 3300, "L2": 50, "R2": 136}
REPORT_BYTE = {"LX": 1, "LY": 2, "RX": 3, "RY": 4, "L2": 8, "R2": 9}
GCC = (0x123, 0x0F0)                       # what the modelled calibration "measures" on sides A and B

# Flash controller model: the saved-settings page can be "programmed" (readable), "erased" or "torn" (a power cut while
# erasing/programming).  A bus read of an erased or torn page faults on the LPC55, so the model records it as a
# violation and stops; reads through the controller (CMD 3) report an ECC error instead, as the hardware does.
SAVED_PAGE = 0x1E000
FLASHCTL = 0x40034000
FLASH = {"state": "programmed", "violations": [], "cmds": [], "buffer": {}, "stale": []}
STALE_ALL: list[tuple[int, int]] = []      # every stale-DONE command in this process, for tests that write after boot
EXTRA_PAGES: set[int] = set()              # pages besides the saved page a test lets the firmware erase/program
VOLTS: dict[tuple[int, int], int] = {}     # (channel, side) -> 12-bit value the modelled ADC returns
FIFO: list[int] = []


def model_syscon(uc, access, addr, size, value, data):
    """Record peripheral writes, and make PRESETCTRLSETn / CLRn act on PRESETCTRLn like the hardware does."""
    if 0x40000000 <= addr < 0x400B0000 or 0xE000E000 <= addr < 0xE000F000:   # peripherals, and the NVIC/SCB
        WRITES.append((addr, value & 0xFFFFFFFF))
    if addr == FLASHCTL + 0xFE8:           # INT_CLR_STATUS
        st = int.from_bytes(uc.mem_read(FLASHCTL + 0xFE0, 4), "little")
        uc.mem_write(FLASHCTL + 0xFE0, (st & ~value & 0xFFFFFFFF).to_bytes(4, "little"))
    if addr == FLASHCTL:                   # CMD
        flash_command(uc, value)
    if addr == ADC + 0x34 and value & 1:   # SWTRIG: run the command chain trigger 0 points at, results to FIFO 0
        rd = lambda a: int.from_bytes(uc.mem_read(a, 4), "little")
        cmd = (rd(ADC + 0xA0) >> 24) & 0xF
        while cmd:
            cmdl = rd(ADC + 0x100 + 8 * (cmd - 1))
            key = (cmdl & 0x1F, (cmdl >> 5) & 3)
            FIFO.append((1 << 31) | (cmd << 24) | (VOLTS.get(key, 0) << 3))
            cmd = (rd(ADC + 0x104 + 8 * (cmd - 1)) >> 24) & 0xF
    for off, op, base in ((0x120, "set", 0x100), (0x140, "clr", 0x100), (0x220, "set", 0x200), (0x240, "clr", 0x200)):
        if SYSCON + off <= addr < SYSCON + off + 12:   # PRESETCTRLn and AHBCLKCTRLn through their SET/CLR registers
            reg = addr - off + base
            cur = int.from_bytes(uc.mem_read(reg, 4), "little")
            new = cur | value if op == "set" else cur & ~value
            uc.mem_write(reg, (new & 0xFFFFFFFF).to_bytes(4, "little"))


def symbol(name: str, source: str | None = None) -> int:
    """Address of a symbol; `source` (a file name) picks one of several statics with the same name."""
    out = subprocess.run(["arm-none-eabi-nm", "-l", str(ELF)], capture_output=True, text=True, check=True).stdout
    found = [(int(m.group(1), 16), m.group(2)) for m in re.finditer(rf"^([0-9a-f]+) \w {re.escape(name)}(?:\t(.*))?$", out, re.M)]
    if source:
        found = [f for f in found if f[1] and re.search(rf"[/\\]{re.escape(source)}:\d+$", f[1])]
    assert len(found) == 1, f"{name}{' in ' + source if source else ''}: {len(found)} matches in {ELF.name}"
    return found[0][0]


def after_report_build() -> int:
    """Address of the instruction right after main's call to report_build (a point where the report is complete)."""
    out = subprocess.run(["arm-none-eabi-objdump", "-d", "--disassemble=main", str(ELF)],
                         capture_output=True, text=True, check=True).stdout
    lines = [l for l in out.splitlines() if re.match(r"^\s+[0-9a-f]+:", l)]
    for i, line in enumerate(lines):
        if "<report_build>" in line and "bl" in line:
            return int(lines[i + 1].split(":")[0], 16)
    raise AssertionError("main does not call report_build")


def flash_command(uc, cmd):
    rd = lambda a: int.from_bytes(uc.mem_read(a, 4), "little")
    start, stop = rd(FLASHCTL + 0x10) << 4, rd(FLASHCTL + 0x14) << 4
    pc = uc.reg_read(UC_ARM_REG_PC)
    if cmd in (3, 4, 8, 12) and (pc < 0x20000000 or not uc.reg_read(UC_ARM_REG_PRIMASK)):
        # Read/erase/program must be issued (and waited for) from RAM with interrupts off: the array cannot serve
        # instruction fetches while it works.  (Command 2, set read mode, is issued from flash by Razer too.)
        STALE_ALL.append((cmd, start, f"issued from {pc:#x}, PRIMASK {uc.reg_read(UC_ARM_REG_PRIMASK)}"))
    if rd(FLASHCTL + 0xFE0) & (1 << 2):
        # On hardware DONE stays set until INT_CLR_STATUS: a wait for DONE would return at once and the next command
        # would go to a busy controller.  Every command must be issued with DONE cleared.
        FLASH["stale"].append((cmd, start))
        STALE_ALL.append((cmd, start))
    status = 1 << 2                                       # DONE
    FLASH["cmds"].append((cmd, start))
    in_page = SAVED_PAGE <= start < SAVED_PAGE + 512
    if cmd == 3:                                          # read 16 bytes into DATAW
        if in_page and FLASH["state"] != "programmed":
            status |= 1 << 3                              # ECC error
        else:
            uc.mem_write(FLASHCTL + 0x80, bytes(uc.mem_read(start, 16)))
    elif cmd == 4 and start & ~511 in EXTRA_PAGES and stop & ~511 == start & ~511:   # a test's own scratch page
        uc.mem_write(start & ~511, b"\xff" * 512)
    elif cmd == 12 and start & ~511 in EXTRA_PAGES:
        for a, d in FLASH["buffer"].items():
            uc.mem_write(a, d)
        FLASH["buffer"].clear()
    elif cmd == 4:                                        # erase range (pages)
        if not SAVED_PAGE <= start <= stop < SAVED_PAGE + 512:
            raise AssertionError(f"erase outside the saved page: {start:#x}..{stop:#x}")
        FLASH["state"] = "erased"
        uc.mem_write(SAVED_PAGE, b"\xff" * 512)
    elif cmd == 8:                                        # 16 bytes into the page buffer
        FLASH["buffer"][start] = bytes(uc.mem_read(FLASHCTL + 0x80, 16))
    elif cmd == 12:                                       # program the page buffer
        page = start & ~511
        if page != SAVED_PAGE or FLASH["state"] != "erased":
            raise AssertionError(f"program of {page:#x} in state {FLASH['state']}")
        for a, d in FLASH["buffer"].items():
            uc.mem_write(a, d)
        FLASH["buffer"].clear()
        FLASH["state"] = "programmed"
    uc.mem_write(FLASHCTL + 0xFE0, status.to_bytes(4, "little"))


# Peripherals whose registers fault on the LPC55 when their AHB clock is off: (base, size, AHBCLKCTRLn, bit, name).
CLOCKED = [(0x400A2000, 0x1000, 2, 17, "USB0 host-slave (USBFSH)"), (0x40084000, 0x1000, 1, 25, "USB0 device"),
           (0x400A0000, 0x1000, 0, 27, "ADC"), (0x40100000, 0x4000, 2, 6, "USB RAM"), (0x40085000, 0x1000, 1, 2, "SCTimer")]
# Of those, the ones with a reset bit at the same position in PRESETCTRLn: touching them while held in reset faults.
IN_RESET_CHECKED = {"USB0 device", "ADC", "SCTimer"}
UNCLOCKED: list[str] = []


def guard_clocks(uc, access, addr, size, value, data):
    for base, length, n, bit, name in CLOCKED:
        if base <= addr < base + length:
            kind = 'write' if access == UC_MEM_WRITE else 'read'
            if not int.from_bytes(uc.mem_read(SYSCON + 0x200 + 4 * n, 4), "little") >> bit & 1:
                UNCLOCKED.append(f"{kind} of {name} at {addr:#x}")
                uc.emu_stop()
            elif name in IN_RESET_CHECKED and int.from_bytes(uc.mem_read(SYSCON + 0x100 + 4 * n, 4), "little") >> bit & 1:
                UNCLOCKED.append(f"{kind} of {name} at {addr:#x} while it is held in reset")
                uc.emu_stop()
            elif name == "SCTimer" and access == UC_MEM_WRITE and 0x40085100 <= addr < 0x40085140 and \
                    int.from_bytes(uc.mem_read(0x40085004, 4), "little") & 0x40004 != 0x40004:
                # MATCH written while either counter half runs (HALT_L / HALT_H clear): a precise bus error on
                # hardware (BFAR 0x40085100 when HALT_H was cleared).
                UNCLOCKED.append(f"write of SCT MATCH at {addr:#x} with a counter half running")
                uc.emu_stop()


def guard_saved_page(uc, access, addr, size, value, data):
    if FLASH["state"] != "programmed":
        FLASH["violations"].append(addr)
        uc.emu_stop()


# Clock dividers and their source selects: a divider only acknowledges a change (REQFLAG clears) while its source
# runs, and the selects reset to 7 ("none").  Setting a divider before its source would hang (or time out).
CLKDIV_SOURCE = {SYSCON + 0x394: SYSCON + 0x2A4, SYSCON + 0x398: SYSCON + 0x2A8,   # ADCCLKDIV, USB0CLKDIV
                 SYSCON + 0x3B4: SYSCON + 0x2F0}                                     # SCTCLKDIV


def model_clkdiv_read(uc, access, addr, size, value, data):
    src = int.from_bytes(uc.mem_read(CLKDIV_SOURCE[addr], 4), "little") & 7
    cur = int.from_bytes(uc.mem_read(addr, 4), "little")
    flag = (1 << 31) if src == 7 else 0
    uc.mem_write(addr, ((cur & ~(1 << 31)) | flag).to_bytes(4, "little"))


def model_adc_read(uc, access, addr, size, value, data):
    if addr == ADC + 0x300:   # RESFIFO0: pop one result, or "not valid" when empty
        uc.mem_write(addr, (FIFO.pop(0) if FIFO else 0).to_bytes(4, "little"))


def run(pressed: set[str], analog: dict[str, int] | None = None, page: bytes | None = None,
        page_state: str = "programmed") -> tuple[bytes, Uc]:
    WRITES.clear()
    FLASH.update(state=page_state, violations=[], cmds=[], buffer={}, stale=[])
    FIFO.clear()
    VOLTS.clear()
    for pin, ch in ADC_PINS.items():
        slot = SLOT[pin]
        VOLTS[ch] = (analog or {}).get(slot, REST[slot])
    image = BIN.read_bytes()
    mu = Uc(UC_ARCH_ARM, UC_MODE_THUMB | UC_MODE_MCLASS)
    mu.mem_map(0x0, 0xA0000)
    mu.mem_write(0x8000, image)
    for base, size in ((0x20000000, 0x40000), (0x04000000, 0x10000), (0x40000000, 0x104000), (0xE0000000, 0x100000)):
        mu.mem_map(base, size)
    for name, (port, pin) in BUTTONS.items():   # pull-ups: 1 = released, 0 = pressed
        mu.mem_write(GPIO + 0x20 * port + pin, b"\x00" if name in pressed else b"\x01")
    if page is not None:
        mu.mem_write(SAVED_PAGE, page)
    mu.hook_add(UC_HOOK_MEM_READ, guard_saved_page, begin=SAVED_PAGE, end=SAVED_PAGE + 511)
    UNCLOCKED.clear()
    for base, length, *_ in CLOCKED:
        mu.hook_add(UC_HOOK_MEM_READ | UC_HOOK_MEM_WRITE, guard_clocks, begin=base, end=base + length - 1)
    mu.mem_write(ADC + 0x14, (1 << 10).to_bytes(4, "little"))         # ADC STAT: calibration ready
    for side, gcc in enumerate(GCC):                                   # ADC GCC: gain calibration measured
        mu.mem_write(ADC + 0xF0 + 4 * side, ((1 << 24) | gcc).to_bytes(4, "little"))
    mu.hook_add(UC_HOOK_MEM_WRITE, model_syscon)
    mu.hook_add(UC_HOOK_MEM_READ, model_adc_read, begin=ADC + 0x300, end=ADC + 0x303)
    for div, src in CLKDIV_SOURCE.items():
        mu.mem_write(src, (7).to_bytes(4, "little"))   # reset value: no clock selected
        mu.hook_add(UC_HOOK_MEM_READ, model_clkdiv_read, begin=div, end=div + 3)
    sp, reset = int.from_bytes(image[0:4], "little"), int.from_bytes(image[4:8], "little")
    mu.reg_write(UC_ARM_REG_SP, sp)
    # The core as Razer's bootloader may hand it over: interrupts masked, SysTick running, VTOR on its own table.
    mu.reg_write(UC_ARM_REG_PRIMASK, 1)
    mu.mem_write(SYST_CSR, (7).to_bytes(4, "little"))
    mu.mem_write(VTOR, (0).to_bytes(4, "little"))
    stop, passes = after_report_build(), [0]

    def hook(uc, addr, size, data):
        passes[0] += 1
        if passes[0] == 3:   # third pass of the main loop: boot and config load are long done
            uc.emu_stop()

    mu.hook_add(UC_HOOK_CODE, hook, begin=stop, end=stop)
    mu.emu_start(reset, 0, count=2_000_000)
    assert not FLASH["violations"], f"bus read of the {FLASH['state']} saved page at {FLASH['violations'][0]:#x}"
    assert not UNCLOCKED, f"access to an unclocked peripheral (BusFault on hardware): {UNCLOCKED[0]}"
    assert not FLASH["stale"], f"flash command issued with DONE still set (cmd, addr): {FLASH['stale'][0]}"
    assert not STALE_ALL, f"flash command issued wrongly: {STALE_ALL[0]}"
    assert passes[0] == 3, "main loop did not run three times"
    return bytes(mu.mem_read(symbol("input_report", "main.c"), 64)), mu   # usb_ds4.c has its own input_report


def main() -> None:
    assert BIN.exists(), "run ./build.sh first"
    failures = 0

    def check(ok: bool, what: str) -> None:
        nonlocal failures
        print(("ok   " if ok else "FAIL ") + what)
        failures += not ok

    base, mu = run(set())
    word = lambda a: int.from_bytes(mu.mem_read(a, 4), "little")
    timeouts = word(symbol("diag") + 12)   # struct diag: magic[8], stages, timeouts
    check(timeouts == 0, f"no start-up wait timed out (diag.timeouts {timeouts:#x}; each one costs ~0.75 s on hardware)")
    check(word(VTOR) == 0x8000, f"VTOR points at our vector table, as Razer's SystemInit does: {word(VTOR):#x}")
    check(mu.reg_read(UC_ARM_REG_PRIMASK) == 0, "interrupts enabled after start-up even if the bootloader masked them")
    check(word(SYST_CSR) == 0, f"SysTick left running by the bootloader is stopped: CSR {word(SYST_CSR):#x}")
    # Before anything else (after starting the cycle counter, DEMCR): VTOR, SysTick, then ICER/ICPR for both NVIC words.
    first = [w for w in WRITES if w[0] != 0xE000EDFC][:6]
    cleared = {a for a, v in first if v == 0xFFFFFFFF}
    check(first[0] == (VTOR, 0x8000) and {NVIC_ICER0, NVIC_ICER0 + 4, NVIC_ICER0 + 0x100, NVIC_ICER0 + 0x104} <= cleared,
          f"start-up first sets VTOR, then disables and un-pends every NVIC line: {[(hex(a), hex(v)) for a, v in first]}")
    clk0 = [v for a, v in WRITES if a == SYSCON + 0x220]
    check(all(any(v & bit for v in clk0) for bit in (1 << 13, 1 << 14, 1 << 15)),
          f"IOCON/GPIO0/GPIO1 clocks enabled (AHBCLKCTRLSET0 writes {[hex(v) for v in clk0]})")
    reg = lambda off: int.from_bytes(mu.mem_read(SYSCON + off, 4), "little")
    check(reg(0x280) == 3 and reg(0x284) == 0 and reg(0x380) & 0xFF == 1,
          f"CPU on FRO_HF / 2 = 48 MHz (MAINCLKSELA {reg(0x280)}, MAINCLKSELB {reg(0x284)}, AHBCLKDIV {reg(0x380):#x})")
    check((reg(0x400) >> 12) & 0xF == 8, f"flash wait states 8 before the clock switch (FMCCR {reg(0x400):#x})")
    order = [a for a, _ in WRITES]
    check(order.index(SYSCON + 0x400) < order.index(SYSCON + 0x280), "wait states set before MAINCLKSELA")
    check(reg(0x2A8) == 3 and reg(0x398) & 0xFF == 1, f"USB0 clock FRO_HF / 2 = 48 MHz (USB0CLKSEL {reg(0x2A8)}, DIV {reg(0x398):#x})")
    portmode = int.from_bytes(mu.mem_read(0x400A205C, 4), "little")
    check(portmode & (1 << 16) != 0, f"USB0 port in device mode (USBFSH PORTMODE {portmode:#x})")
    pmc = [v for a, v in WRITES if a == 0x400200C8]
    check(any(v & (1 << 11) for v in pmc) and any(v & (1 << 5) for v in pmc), "FRO192M and USB0 PHY powered (PDRUNCFGCLR0)")
    # PDRUNCFGCLR0, plus RESETCTRL and BODVBAT (the brown-out set-up Razer does before its flash init): nothing else.
    check(not any(0x40020000 <= a < 0x40021000 and a not in (0x400200C8, 0x40020008, 0x40020030) for a, _ in WRITES),
          "no other PMC writes (core voltage untouched)")
    check(any(a == 0x40020030 and v & 0x7F == 0x53 for a, v in WRITES) and
          any(a == 0x40020008 and v & 2 for a, v in WRITES), "VBAT brown-out detector and reset set up as Razer's")
    out_pins = (1 << 18) | (1 << 12) | (1 << 9)   # P1_18, P1_12, P1_9: GPIO outputs Razer drives high (pot supply)
    dir1 = word(GPIO + 0x2004)
    set1 = 0
    for a, v in WRITES:   # SET1 is write-only (each write sets its bits): OR every write
        set1 |= v if a == GPIO + 0x2204 else 0
    check(dir1 & out_pins == out_pins and set1 & out_pins == out_pins,
          f"P1_18, P1_12, P1_9 driven high as Razer does (DIR1 {dir1:#x}, SET1 {set1:#x})")
    eplist, databuf = word(0x40084008), word(0x4008400C)
    check(0x40100000 <= eplist < 0x40104000 and databuf == 0x40000000,
          f"USB endpoint list and buffers in the USB RAM, as Razer's: EPLISTSTART {eplist:#x}, DATABUFSTART {databuf:#x}")
    nvic = int.from_bytes(mu.mem_read(0xE000E100, 4), "little")
    check(nvic & (1 << 28) != 0, f"USB0 interrupt enabled in the NVIC (ISER0 {nvic:#x})")
    bad = [f"P{p}_{n}" for p, n in BUTTONS.values()
           if int.from_bytes(mu.mem_read(IOCON + 0x80 * p + 4 * n, 4), "little") != 0x120]
    check(not bad, f"button pins are digital inputs with pull-up {bad or ''}")
    bad = [f"P{p}_{n}" for p, n in ANALOG if int.from_bytes(mu.mem_read(IOCON + 0x80 * p + 4 * n, 4), "little") != 0x400]
    check(not bad, f"analog pins are in analog mode {bad or ''}")
    adc = lambda off: int.from_bytes(mu.mem_read(ADC + off, 4), "little")
    check(reg(0x2A4) == 0 and reg(0x394) & 0xFF == 7, f"ADC clock = main clock / 8 = 12 MHz (ADCCLKSEL {reg(0x2A4)}, DIV {reg(0x394):#x})")
    check(any(v & (1 << 19) for v in pmc), "ADC regulator powered (PDRUNCFGCLR0 bit 19)")
    check(adc(0x20) == 0x10800040, f"ADC CFG as Razer's (0x10800040): {adc(0x20):#x}")
    want_gcr = [((1 << 33) // ((1 << 17) - g)) | (1 << 24) for g in GCC]
    check([adc(0xF8), adc(0xFC)] == want_gcr, f"gain calibration written from GCC: {[hex(adc(0xF8)), hex(adc(0xFC))]}")
    cmds = sorted((adc(0x100 + 8 * i) & 0x1F, (adc(0x100 + 8 * i) >> 5) & 3) for i in range(6))
    check(cmds == sorted(ADC_PINS.values()), f"one command per analog pin, same channels as Razer's: {cmds}")
    from_image = mu.mem_read(symbol("kcfg_from_image"), 1)[0]
    check(from_image == 1, f"the image's config block is valid and in use (CRC stamped by build.sh): {from_image}")
    check(base[0] == 0x01 and base[5] == 0x08 and base[6] == 0 and base[30] == 0x1B,
          f"neutral report: id {base[0]:#x}, hat/face {base[5]:#x}, buttons {base[6]:#x}, status {base[30]:#x}")
    check(all(0x78 <= b <= 0x88 for b in base[1:5]) and base[8] == 0 and base[9] == 0,
          f"at rest: sticks centred {list(base[1:5])}, triggers released {list(base[8:10])}")

    for slot, byte in REPORT_BYTE.items():
        rep, _ = run(set(), {slot: MOVED[slot]})
        moved = [i for i in (1, 2, 3, 4, 8, 9) if abs(rep[i] - base[i]) > 0x20]
        check(moved == [byte], f"{slot} ({[p for p, s in SLOT.items() if s == slot][0]}) moves report byte {byte} only: "
              f"{base[byte]} -> {rep[byte]}, moved {moved}")
        # Directions measured on the controller (board_v2pro.c): a higher pin voltage is right on LX/RX, down on LY,
        # up on RY; a lower one is a pressed trigger.  DS4: X 255 = right, Y 0 = up, triggers 255 = pressed.
        up = rep[byte] > base[byte]
        want_up = {"LX": True, "LY": True, "RX": True, "RY": False, "L2": True, "R2": True}[slot]
        check(up == want_up, f"  ...in the controller's direction ({'higher' if up else 'lower'} report value)")

    for name in BUTTONS:
        rep, _ = run({name})
        changed = rep[5:8] != base[5:8]
        if name in UNMAPPED:
            check(not changed, f"{name:5s} has no DS4 mapping yet, report unchanged")
        else:
            check(changed, f"{name:5s} changes the report: {base[5:8].hex(' ')} -> {rep[5:8].hex(' ')}")

    # Touchpad: one packet, no finger at rest; M1 / M2 = click + a finger in the middle of the left / right half.
    check(base[33] == 1 and base[35] & 0x80 and base[39] & 0x80, f"touchpad at rest: 1 packet, no fingers {base[33:40].hex(' ')}")
    for name, want_x in (("M1", 480), ("M2", 1440)):
        rep, _ = run({name})
        x, y = rep[36] | (rep[37] & 0x0F) << 8, rep[37] >> 4 | rep[38] << 4
        check(rep[7] & 0x02 and not rep[35] & 0x80 and (x, y) == (want_x, 471) and rep[39] & 0x80,
              f"{name}: touchpad click with one finger at ({x}, {y}), want ({want_x}, 471)")

    print(f"{failures} failure(s)")
    sys.exit(1 if failures else 0)


if __name__ == "__main__":
    main()
