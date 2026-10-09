"""Kishi V2 Pro bootloader tool: enter and leave Razer's bootloader, restore Razer's own firmware, flash KishiDS's.

  python tools/v2pro_dfu.py plan  <image.hex>         # offline: check the image and print what flash would send
  python tools/v2pro_dfu.py enter                     # V2 Pro -> bootloader (1532:110E); erases nothing
  python tools/v2pro_dfu.py exit                      # bootloader -> application (DFUExit); erases nothing
  python tools/v2pro_dfu.py flash <firmwareHex.hex>   # RECOVERY: erase, program and verify Razer's 2.2 image, then exit
  python tools/v2pro_dfu.py sigtest <official.hex> <out.hex>         # offline: make the one-byte signature-test image
  python tools/v2pro_dfu.py flash <out.hex> --signature-test         # flash it (phase 4 of V2PRO_HARDWARE_TEST.md)
  python tools/v2pro_dfu.py flash <kishi_v2pro_ds4.hex> --custom     # our firmware (phase 5)
  python tools/v2pro_dfu.py readdiag                  # bootloader, read-only: the record a diagnostic build left

`enter` works from Razer's firmware (SetDeviceMode 01 00) and from ours (KishiDS live command 7 on the DS4 interface).
Every command that changes the controller asks you to type "yes" first (--yes skips the question).

Safety rules built in:
  - `flash` accepts images whose SHA-256 is listed in OFFICIAL_IMAGES (Razer's 2.2 for the V2 Pro), the signature-test
    image derived from it (TEST_IMAGES) with --signature-test, and, with --custom, only a KishiDS V2 Pro build that
    passes check_custom(): linked at 0x8000 and inside Razer's own image span (the erase range proven on hardware), a
    sane vector table, a stamped KishiDS config block, and the recovery marker (the build has the ways back into
    Razer's bootloader: View + Menu at power-on, faults, live command 7; see v2pro-firmware/bootloader.h).
  - No erase or program command can address anything below APP_BASE (0x8000), where Razer's bootloader lives.
  - The interface and transaction ID are always found with read-only commands before anything is written.
  - If a step fails after the erase, DFUAbort is sent and the controller is left in the bootloader, where
    `flash` can be run again.

UNTESTED ON HARDWARE. Command bytes and layouts come from Razer's libRazer_IoT_SDK.so; see ../KISHI_V2_PRO.md.
Assumptions that only a real controller can confirm are marked "ASSUMPTION" below.
"""

from __future__ import annotations

import argparse
import binascii
import hashlib
import struct
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent / "python"))
sys.path.insert(0, str(Path(__file__).resolve().parent))
import razer_v25 as rz  # noqa: E402

# Class 0x00 (normal mode)
CMD_SET_DEVICE_MODE = (0x00, 0x04)
CMD_GET_FIRMWARE = (0x00, 0x81)
MODE_BOOTLOADER = bytes([0x01, 0x00])  # what Razer's SDK sends before a firmware update

# Class 0x10 (bootloader)
DFU = 0x10
DFU_ERASE, DFU_PROGRAM, DFU_ABORT, DFU_EXIT, DFU_INFO, DFU_VERIFY = 0x01, 0x02, 0x04, 0x05, 0x80, 0x83

APP_BASE = 0x8000
APP_LIMIT = 0x1EC00  # end of Razer's 2.2 image: the only erase range proven on hardware (phases 3 and 4)
BLOCK = 64  # bytes per DFUProgram / DFUVerify; Razer's updater uses 0x40 (5 header bytes + 64 fit one packet)

# KishiDS firmware (v2pro-firmware): it enumerates as a DS4 and speaks the KishiDS live protocol (ds4-firmware/live.h).
DS4_VID, DS4_PID = 0x054C, 0x05C4
LIVE_REPORT, LIVE_SIGNATURE, LIVE_PROTOCOL, LIVE_REPORT_LEN, LIVE_BOOTLOADER = 0xAC, 0x4B, 1, 58, 7
RECOVERY_MARKER = b"KISHIDS-V2PRO-RECOVERY-1"   # v2pro-firmware/bootloader.c
CONFIG_MARKER = b"KISHICFG" + struct.pack("<HH", 1, 256)   # tools/finalize_image.py

OFFICIAL_IMAGES = {
    "e1d440a03cef8668aa465f900a95bba5666e32c5a9a47acbeeebd3b7487a3619":
        "Razer Kishi V2 Pro firmware 2.2 (BiancaFw/T1/02.02.00.00/firmwareHex.hex)",
}


# Signature test: Razer's 2.2 with one byte changed, the "K" of the USB product string "Razer Kishi V2 Pro" -> "X".
# The string sits in the image's compressed initialised data; 0x1E6EA is a literal byte of that stream.  Verified by
# emulating the start-up of both images: the decompressed RAM differs in exactly one byte (the string's "K", at
# 0x2000084E) and the boot runs the same number of instructions, so nothing else changes and no self-check of the
# image was seen on that path.  If the controller then enumerates as "Razer Xishi V2 Pro", the bootloader accepted a
# modified image: no signature check.
SIGTEST_ADDR, SIGTEST_OLD, SIGTEST_NEW = 0x1E6EA, 0x4B, 0x58
TEST_IMAGES = {
    "be5c248d34d489fdb9084c50f9a6aa333079f80736087486ac7d067e26b169d1":
        "Signature test: Razer 2.2 with the USB product string changed to \"Razer Xishi V2 Pro\"",
}


# ---------------------------------------------------------------- image

def load_hex(path: Path) -> tuple[int, bytes]:
    """Parse Intel HEX into (base address, contiguous bytes). Rejects gaps and anything below APP_BASE."""
    mem: dict[int, int] = {}
    upper = 0
    for n, line in enumerate(path.read_text().splitlines(), 1):
        line = line.strip()
        if not line:
            continue
        if not line.startswith(":"):
            raise ValueError(f"line {n}: not an Intel HEX record")
        rec = bytes.fromhex(line[1:])
        count, addr, rtype, payload = rec[0], (rec[1] << 8) | rec[2], rec[3], rec[4 : 4 + rec[0]]
        if len(rec) != count + 5 or sum(rec) & 0xFF:
            raise ValueError(f"line {n}: bad length or checksum")
        if rtype == 0x00:
            for i, b in enumerate(payload):
                mem[upper + addr + i] = b
        elif rtype == 0x01:
            break
        elif rtype == 0x02:
            upper = int.from_bytes(payload, "big") << 4
        elif rtype == 0x04:
            upper = int.from_bytes(payload, "big") << 16
        elif rtype not in (0x03, 0x05):
            raise ValueError(f"line {n}: unsupported record type {rtype:#04x}")
    if not mem:
        raise ValueError("image is empty")
    lo, hi = min(mem), max(mem)
    if lo < APP_BASE:
        raise ValueError(f"image starts at {lo:#x}, below the application base {APP_BASE:#x} (bootloader area)")
    if len(mem) != hi - lo + 1:
        raise ValueError("image has gaps; only contiguous images are supported")
    return lo, bytes(mem[a] for a in range(lo, hi + 1))


def check_official(path: Path) -> str:
    digest = hashlib.sha256(path.read_bytes()).hexdigest()
    if digest not in OFFICIAL_IMAGES:
        raise SystemExit(f"{path.name}: SHA-256 {digest} is not an official image this tool will flash.")
    return OFFICIAL_IMAGES[digest]


def custom_problems(base: int, image: bytes) -> list[str]:
    """Why a KishiDS V2 Pro build must not be flashed (empty list: it may)."""
    problems = []
    end = base + len(image)
    if base != APP_BASE:
        problems.append(f"starts at {base:#x}, not {APP_BASE:#x}")
    if end > APP_LIMIT:
        problems.append(f"ends at {end:#x}, past Razer's image span ({APP_LIMIT:#x})")
    if len(image) < 64:
        return problems + ["too small to hold a vector table"]
    vectors = struct.unpack_from("<16I", image)
    sp = vectors[0]
    if not (0x04000000 < sp <= 0x04008000 or 0x20000000 < sp <= 0x20040000):
        problems.append(f"initial stack pointer {sp:#x} is not in SRAMX or SRAM")
    for i, v in enumerate(vectors[1:], 1):
        if v and (not v & 1 or not base <= (v & ~1) < end):
            problems.append(f"vector {i} = {v:#x} is not Thumb code inside the image")
    if not vectors[1]:
        problems.append("no reset vector")
    if image.count(RECOVERY_MARKER) != 1:
        problems.append(f"recovery marker {RECOVERY_MARKER.decode()} not found exactly once (no way back to the bootloader)")
    if image.count(CONFIG_MARKER) != 1:
        problems.append("KishiDS config block not found exactly once")
    else:
        cfg = image.find(CONFIG_MARKER)
        stored = struct.unpack_from("<I", image, cfg + 12)[0]
        if binascii.crc32(image[cfg + 16 : cfg + 256]) & 0xFFFFFFFF != stored:
            problems.append("config block CRC is not stamped (build with v2pro-firmware/build.sh)")
    return problems


def check_custom(path: Path) -> str:
    try:
        base, image = load_hex(path)
    except ValueError as exc:
        raise SystemExit(f"{path.name}: {exc}") from None
    problems = custom_problems(base, image)
    if problems:
        raise SystemExit(f"{path.name} is not a KishiDS V2 Pro build this tool will flash:\n  - " + "\n  - ".join(problems))
    digest = hashlib.sha256(path.read_bytes()).hexdigest()
    return f"KishiDS V2 Pro firmware {path.name} (SHA-256 {digest[:16]}...)"


def check_image(path: Path, allow_test: bool, allow_custom: bool = False) -> tuple[str, str]:
    """(description, kind) with kind "official", "test" (signature test, needs allow_test) or "custom" (allow_custom)."""
    digest = hashlib.sha256(path.read_bytes()).hexdigest()
    if digest in OFFICIAL_IMAGES:
        return OFFICIAL_IMAGES[digest], "official"
    if digest in TEST_IMAGES:
        if not allow_test:
            raise SystemExit(f"{path.name} is the signature-test image: add --signature-test to flash it.")
        return TEST_IMAGES[digest], "test"
    if allow_custom:
        return check_custom(path), "custom"
    raise SystemExit(f"{path.name}: SHA-256 {digest} is not an image this tool will flash "
                     "(for a KishiDS V2 Pro build add --custom).")


def make_sigtest(official: Path, out: Path) -> str:
    """Write the signature-test image: the official .hex with one data byte (and its record checksum) changed."""
    check_official(official)
    # Keep the file byte-for-byte except that one record (Razer's file uses CRLF line endings).
    lines, upper, done = official.read_bytes().decode("ascii").splitlines(keepends=True), 0, False
    for n, line in enumerate(lines):
        rec = bytearray(bytes.fromhex(line.strip()[1:]))
        count, addr, rtype = rec[0], (rec[1] << 8) | rec[2], rec[3]
        if rtype == 0x04:
            upper = int.from_bytes(rec[4:6], "big") << 16
        elif rtype == 0x02:
            upper = int.from_bytes(rec[4:6], "big") << 4
        elif rtype == 0x00 and upper + addr <= SIGTEST_ADDR < upper + addr + count:
            i = 4 + SIGTEST_ADDR - (upper + addr)
            if rec[i] != SIGTEST_OLD:
                raise SystemExit(f"unexpected byte {rec[i]:#04x} at {SIGTEST_ADDR:#x} (expected {SIGTEST_OLD:#04x})")
            rec[i] = SIGTEST_NEW
            rec[-1] = (-sum(rec[:-1])) & 0xFF
            lines[n] = ":" + rec.hex().upper() + line[len(line.rstrip("\r\n")):]
            done = True
            break
    if not done:
        raise SystemExit(f"{SIGTEST_ADDR:#x} not found in {official.name}")
    out.write_bytes("".join(lines).encode("ascii"))
    base, image = load_hex(out)
    _, original = load_hex(official)
    diff = [i for i in range(len(image)) if image[i] != original[i]]
    assert diff == [SIGTEST_ADDR - base], diff
    digest = hashlib.sha256(out.read_bytes()).hexdigest()
    if digest not in TEST_IMAGES:
        raise SystemExit(f"{out.name}: SHA-256 {digest} is not the expected signature-test image")
    return digest


# ---------------------------------------------------------------- packets

def be32(value: int) -> bytes:
    return value.to_bytes(4, "big")


def erase_data(start: int, end: int) -> bytes:
    if start < APP_BASE or end < start:
        raise ValueError(f"refusing erase {start:#x}..{end:#x}")
    return be32(start) + be32(end)


def program_data(addr: int, chunk: bytes) -> bytes:
    if addr < APP_BASE or not 0 < len(chunk) <= rz.DATA_MAX - 5:
        raise ValueError(f"refusing program at {addr:#x} ({len(chunk)} bytes)")
    return bytes([len(chunk)]) + be32(addr) + chunk


def verify_data(addr: int, length: int) -> tuple[bytes, int]:
    # Data size counts the n bytes the device fills in, as in Razer's builder.
    return bytes([length]) + be32(addr), length + 5


def erase_range(base: int, image: bytes) -> tuple[int, int]:
    # ASSUMPTION: EndAddress is the image's last byte. Razer's updater derives it from the hex file; whether the
    # bootloader treats it as inclusive or exclusive is unknown, but the last byte selects the same flash pages
    # either way (the 2.2 image ends on a 512-byte page boundary).
    return base, base + len(image) - 1


# ---------------------------------------------------------------- device

def confirm(what: str, assume_yes: bool) -> None:
    print(what)
    if assume_yes:
        return
    if input('Type "yes" to continue: ').strip().lower() != "yes":
        raise SystemExit("Cancelled.")


def open_v2pro(tid: int | None) -> rz.Link:
    def answers(link: rz.Link) -> bool:
        r = link.command(*CMD_GET_FIRMWARE, size=4, timeout=0.5)
        return bool(r and r.ok and r.cid == CMD_GET_FIRMWARE[1])

    link = rz.open_link(rz.V2_PRO_PIDS, answers, (tid,) if tid else rz.TIDS)
    if not link:
        raise SystemExit("No Kishi V2 Pro answered (1532:0717 / 0718). Run v2pro_probe.py list / info first.")
    return link


def open_bootloader(tid: int | None) -> tuple[rz.Link, bytes]:
    info = {}

    def answers(link: rz.Link) -> bool:
        r = link.command(DFU, DFU_INFO, size=0x50, timeout=1.0)
        if r and r.ok and r.cid == DFU_INFO:
            info["data"] = r.data
            return True
        return False

    link = rz.open_link((rz.BOOTLOADER_PID,), answers, (tid,) if tid else rz.TIDS)
    if not link:
        raise SystemExit("The Razer bootloader (1532:110E) is not connected or did not answer GetDFUDeviceInformation.")
    return link, info["data"]


def describe_link(link: rz.Link) -> str:
    i = link.info
    return f"{rz.KNOWN_PIDS[link.pid]}, interface {i['interface_number']}, transaction ID {link.tid:#04x}"


def wait_report(pids, seconds: float, label: str) -> dict | None:
    print(f"Waiting up to {seconds:.0f} s for {label}...")
    found = rz.wait_for(pids, seconds)
    if found:
        print(f"  found {found['vendor_id']:04x}:{found['product_id']:04x} ({rz.KNOWN_PIDS[found['product_id']]})")
    return found


def ds4_interfaces() -> list[dict]:
    import hid

    return hid.enumerate(DS4_VID, DS4_PID)


def open_hid(path: bytes):
    import hid

    dev = hid.device()
    dev.open_path(path)
    return dev


def open_kishids():
    """The first DS4 interface that answers the KishiDS live report (protocol 1): our firmware, never a real DS4."""
    for info in ds4_interfaces():
        try:
            dev = open_hid(info["path"])
        except OSError:
            continue
        try:
            rep = bytes(dev.get_feature_report(LIVE_REPORT, LIVE_REPORT_LEN))
            if len(rep) >= 4 and rep[0] == LIVE_REPORT and rep[1] == LIVE_PROTOCOL:
                return dev
        except OSError:
            pass
        dev.close()
    return None


def enter_from_kishids(args) -> None:
    dev = open_kishids()
    if not dev:
        raise SystemExit("A DS4 (054C:05C4) is connected but none answers the KishiDS live report: not our firmware.")
    print("Connected: KishiDS firmware (DS4 054C:05C4, live protocol 1)")
    try:
        confirm("This sends KishiDS live command 7: restart into Razer's bootloader. Nothing is erased.\n"
                "To leave it again: v2pro_dfu.py exit, or flash Razer's 2.2 image.", args.yes)
        dev.send_feature_report(bytes([LIVE_REPORT, LIVE_SIGNATURE, LIVE_BOOTLOADER, 0]) + bytes(LIVE_REPORT_LEN - 4))
    except OSError as exc:  # the controller may re-enumerate before the transfer completes
        print(f"Live command 7: no acknowledgement ({exc}); the controller may already be restarting.")
    finally:
        dev.close()


# ---------------------------------------------------------------- commands

def cmd_enter(args) -> None:
    if not rz.interfaces(rz.V2_PRO_PIDS) and ds4_interfaces():
        enter_from_kishids(args)
        if not wait_report((rz.BOOTLOADER_PID,), 15, "the bootloader (1532:110E)"):
            raise SystemExit("The bootloader did not appear. If the controller still shows up as a DS4, unplug it, hold\n"
                             "View + Menu and plug it back in (that enters the bootloader without USB).")
        link, info = open_bootloader(args.tid)
        print(f"Bootloader answers: {describe_link(link)}\nGetDFUDeviceInformation: {info.hex(' ')}")
        link.close()
        return
    link = open_v2pro(args.tid)
    fw = link.command(*CMD_GET_FIRMWARE, size=4)
    print(f"Connected: {describe_link(link)}; firmware {'.'.join(map(str, fw.data))}")
    confirm("This switches the controller into Razer's bootloader (SetDeviceMode 01 00). Nothing is erased.\n"
            "To leave it again: v2pro_dfu.py exit (or, if that fails, v2pro_dfu.py flash with Razer's 2.2 image).",
            args.yes)
    try:
        r = link.command(*CMD_SET_DEVICE_MODE, MODE_BOOTLOADER, timeout=1.0)
        print(f"SetDeviceMode reply: {r}")
    except OSError as exc:  # the controller may re-enumerate before answering
        print(f"SetDeviceMode: no reply ({exc}); the controller may already be restarting.")
    finally:
        link.close()
    if not wait_report((rz.BOOTLOADER_PID,), 15, "the bootloader (1532:110E)"):
        raise SystemExit("The bootloader did not appear. Check v2pro_probe.py list.")
    link, info = open_bootloader(args.tid)
    print(f"Bootloader answers: {describe_link(link)}\nGetDFUDeviceInformation: {info.hex(' ')}")
    link.close()


def cmd_exit(args) -> None:
    link, info = open_bootloader(args.tid)
    print(f"Connected: {describe_link(link)}\nGetDFUDeviceInformation: {info.hex(' ')}")
    confirm("This sends DFUExit (nothing is erased or written).", args.yes)
    try:
        r = link.command(DFU, DFU_EXIT, timeout=2.0)
        print(f"DFUExit reply: {r}")
    except OSError as exc:
        print(f"DFUExit: no reply ({exc}); the controller may already be restarting.")
    finally:
        link.close()
    print("Waiting up to 15 s for the application (Razer's 1532:0717/0718 or KishiDS's DS4 054C:05C4)...")
    end = time.monotonic() + 15
    while time.monotonic() < end and not rz.interfaces(rz.V2_PRO_PIDS) and not ds4_interfaces():
        time.sleep(0.25)
    if rz.interfaces(rz.V2_PRO_PIDS):
        print("Back in the application (Razer's firmware).")
    elif ds4_interfaces():
        print("Back in the application (KishiDS firmware, DS4).")
    elif rz.interfaces((rz.BOOTLOADER_PID,)):
        print("Still in the bootloader: DFUExit did not start the application. Restore with:\n"
              "  python tools/v2pro_dfu.py flash <Razer 2.2 firmwareHex.hex>")
    else:
        print("Neither the application nor the bootloader enumerated. Unplug and replug, then run v2pro_probe.py list.")


def plan(base: int, image: bytes, tid: int) -> list[tuple[str, bytes]]:
    start, end = erase_range(base, image)
    steps = [("GetDFUDeviceInformation", rz.packet(tid, DFU, DFU_INFO, size=0x50)),
             (f"DFUErase {start:#x}..{end:#x}", rz.packet(tid, DFU, DFU_ERASE, erase_data(start, end)))]
    for off in range(0, len(image), BLOCK):
        chunk = image[off : off + BLOCK]
        steps.append((f"DFUProgram {base + off:#x}", rz.packet(tid, DFU, DFU_PROGRAM, program_data(base + off, chunk))))
    for off in range(0, len(image), BLOCK):
        n = len(image[off : off + BLOCK])
        data, size = verify_data(base + off, n)
        steps.append((f"DFUVerify {base + off:#x}", rz.packet(tid, DFU, DFU_VERIFY, data, size)))
    steps.append(("DFUExit", rz.packet(tid, DFU, DFU_EXIT)))
    return steps


# v2pro-firmware/diag.h and diag.c: the record a diagnostic build writes to the saved-settings page before it restarts
# into the bootloader.  Read-only here: DFUVerify returns the flash contents.
DIAG_ADDR, DIAG_MAGIC = 0x1E200, b"KDSDIAG1"   # v2pro-firmware/diag.c DIAG_RECORD_ADDR
DIAG_STAGES = ("reset", "combo checked", "clock_init", "board_init", "config", "usb init", "main loop", "USB interrupt",
               "mounted", "configured")
DIAG_WAITS = ("flash read mode", "AHBCLKDIV", "USB0CLKDIV", "PRESETCTRL set", "PRESETCTRL clear", "ADCCLKDIV",
              "ADC offset cal", "ADC gain cal", "ADC cal ready", "flash command")
# The diagnostic build's timing channel (diag.c signal_and_enter): back in the bootloader after base + unit x (1 + bits).
# Faults come at start-up and the bootloader re-enumerates in ~0.65 s after the reset (measured), hence their base.
DIAG_BASE_S, DIAG_UNIT_S, DIAG_FAULT_BASE_S = 4.4, 1.5, 0.65
DIAG_USB_BITS = ("pull-up connected (DCON)", "VBUS seen", "USB interrupts", "device enabled (DEV_EN)")
DIAG_VECTORS = {2: "NMI", 3: "HardFault", 4: "MemManage", 5: "BusFault", 6: "UsageFault", 7: "SecureFault",
                11: "SVCall", 12: "DebugMonitor", 14: "PendSV", 15: "SysTick"}
DIAG_REGS = ("USB0 DEVCMDSTAT", "USB0 INFO", "USB0 INTSTAT", "USB0 INTEN", "USB0 EPLISTSTART", "USB0 DATABUFSTART",
             "AHBCLKCTRL0", "AHBCLKCTRL1", "AHBCLKCTRL2", "PRESETCTRL0", "PRESETCTRL1", "PRESETCTRL2",
             "MAINCLKSELA", "MAINCLKSELB", "AHBCLKDIV", "USB0CLKSEL", "USB0CLKDIV", "ADCCLKSEL", "ADCCLKDIV", "FMCCR",
             "PMC PDRUNCFG0", "FRO192M_CTRL", "FRO192M_STATUS", "USBFSH PORTMODE", "FLASH INT_STATUS",
             "VTOR", "ICSR", "NVIC ISER0", "NVIC ISER1", "NVIC ISPR0", "ADC STAT", "ADC CTRL")


def decode_diag(page: bytes) -> str:
    if page[:8] != DIAG_MAGIC:
        what = ("all 0xFF: erased but not programmed" if page == b"\xff" * len(page) else
                "all zeros: Razer's leftover bytes, nothing was erased" if not any(page) else "unknown contents")
        return f"No diagnostic record at {DIAG_ADDR:#x}: {what} (starts {page[:16].hex(' ')})."
    stages, timeouts, irqs, loops, primask = struct.unpack_from("<5I", page, 8)
    vector, cfsr, hfsr, bfar, pc, lr = struct.unpack_from("<6I", page, 28)
    regs = struct.unpack_from(f"<{len(DIAG_REGS)}I", page, 52)
    cause = ("USB not configured 3 s after start-up" if not vector else
             f"exception {vector} ({DIAG_VECTORS.get(vector, 'interrupt ' + str(vector - 16))}) at PC {pc:#010x}, "
             f"LR {lr:#010x}; CFSR {cfsr:#010x} HFSR {hfsr:#010x} BFAR {bfar:#010x}")
    lines = ["Diagnostic record (KDSDIAG1):", f"  reported because: {cause}",
             "  stages reached: " + ", ".join(n for i, n in enumerate(DIAG_STAGES) if stages >> i & 1),
             "  stages missing: " + (", ".join(n for i, n in enumerate(DIAG_STAGES) if not stages >> i & 1) or "-"),
             "  waits that timed out: " + (", ".join(n for i, n in enumerate(DIAG_WAITS) if timeouts >> i & 1) or "none"),
             f"  USB interrupts: {irqs}   main loop passes: {loops}   PRIMASK: {primask}"]
    lines += [f"  {name:18s} {value:#010x}" for name, value in zip(DIAG_REGS, regs)]
    return "\n".join(lines)


def cmd_readdiag(args) -> None:
    link, info = open_bootloader(args.tid)
    print(f"Connected: {describe_link(link)}  (read-only: DFUVerify of {DIAG_ADDR:#x}..{DIAG_ADDR + 511:#x})")
    page = b""
    try:
        for off in range(0, 512, BLOCK):
            data, size = verify_data(DIAG_ADDR + off, BLOCK)
            r = expect(link.command(DFU, DFU_VERIFY, data, size, timeout=2.0), DFU_VERIFY, f"DFUVerify {DIAG_ADDR + off:#x}")
            page += bytes(r.data[5 : 5 + BLOCK])
    finally:
        link.close()
    out = Path(__file__).resolve().parents[1] / "device-backups" / "diag_page.bin"
    out.parent.mkdir(exist_ok=True)
    out.write_bytes(page)
    print(decode_diag(page) + f"\n(raw page saved to {out})")


def cmd_sigtest(args) -> None:
    digest = make_sigtest(args.image, args.out)
    print(f"Wrote {args.out}: Razer 2.2 with {SIGTEST_ADDR:#x} changed {SIGTEST_OLD:#04x} -> {SIGTEST_NEW:#04x} "
          f"(\"Kishi\" -> \"Xishi\" in the USB product string)\nSHA-256 {digest}\n"
          f"Flash it with: python tools/v2pro_dfu.py flash {args.out} --signature-test")


def cmd_plan(args) -> None:
    name, _ = check_image(args.image, allow_test=True, allow_custom=True)
    base, image = load_hex(args.image)
    steps = plan(base, image, args.tid or rz.TIDS[0])
    print(f"{name}\n  {len(image)} bytes at {base:#x}..{base + len(image) - 1:#x}, {len(image) // BLOCK} blocks of {BLOCK}")
    counts = {}
    for label, _ in steps:
        counts[label.split()[0]] = counts.get(label.split()[0], 0) + 1
    print("  packets: " + ", ".join(f"{k} x{v}" for k, v in counts.items()))
    for label, pkt in steps[:3] + steps[-1:]:
        print(f"  {label:32s} {pkt[:16].hex(' ')} ... crc={pkt[88]:02x}")


def expect(r: rz.Reply | None, cid: int, what: str) -> rz.Reply:
    if r is None or not r.ok or r.cid != cid or not r.crc_ok:
        raise RuntimeError(f"{what}: {r if r else 'no reply'}")
    return r


def report_custom_boot(seconds: float = 15) -> None:
    """After flashing our firmware: did it come up as a DS4, fall back to the bootloader, or show nothing?"""
    print(f"Waiting up to {seconds:.0f} s for the KishiDS firmware (DS4 054C:05C4)...")
    start = time.monotonic()
    # Right after DFUExit the bootloader can still be listed for a moment: wait for it to go (up to 3 s) before
    # deciding, or a firmware that boots fine would be reported as "back in the bootloader".
    while time.monotonic() < start + 3 and rz.interfaces((rz.BOOTLOADER_PID,)):
        time.sleep(0.1)
    end = max(start + seconds, time.monotonic() + 1)   # still listed after 3 s: it really is back in the bootloader
    while time.monotonic() < end:
        if ds4_interfaces():
            print(f"(DS4 enumerated {time.monotonic() - start:.1f} s after DFUExit)")
            dev = open_kishids()
            if dev:
                dev.close()
                print("RESULT: our firmware booted: it enumerates as a DS4 and answers the KishiDS live report.")
            else:
                print("RESULT: a DS4 enumerated but does not answer the KishiDS live report. Note this, then go back to\n"
                      "the bootloader (unplug, hold View + Menu, plug in) and restore Razer's image.")
            return
        if rz.interfaces((rz.BOOTLOADER_PID,)):
            took = time.monotonic() - start
            print(f"(bootloader back {took:.1f} s after DFUExit)")
            units = round((took - DIAG_BASE_S) / DIAG_UNIT_S)
            if 1 <= units <= 16:
                bits = units - 1
                print("diagnostic build, USB state by timing: " +
                      ", ".join(f"{name} {'yes' if bits >> i & 1 else 'no'}" for i, name in enumerate(DIAG_USB_BITS)))
            # A fault reports at once (no 3 s timer): units 20 + the last DIAG_STEP.
            fault_units = round((took - DIAG_FAULT_BASE_S) / DIAG_UNIT_S)
            if fault_units >= 20:
                print(f"diagnostic build: a FAULT after DIAG_STEP({fault_units - 20}) (timing)")
            print("RESULT: back in Razer's bootloader: our firmware faulted, a diagnostic build reported (USB did not come\n"
                  "up; read it with: python tools/v2pro_dfu.py readdiag), or View + Menu was held. Nothing is lost;\n"
                  "restore with: python tools/v2pro_dfu.py flash <Razer 2.2 .hex>")
            return
        time.sleep(0.25)
    print("RESULT: nothing enumerated: our firmware runs but USB did not come up. Unplug, hold View + Menu, plug back\n"
          "in (Razer's bootloader appears as 1532:110E), then restore Razer's image with flash <Razer 2.2 .hex>.")


def cmd_flash(args) -> None:
    name, kind = check_image(args.image, args.signature_test, getattr(args, "custom", False))
    is_test = kind == "test"
    base, image = load_hex(args.image)
    link, info = open_bootloader(args.tid)
    print(f"Image: {name}, {len(image)} bytes at {base:#x}\nConnected: {describe_link(link)}\n"
          f"GetDFUDeviceInformation: {info.hex(' ')}")
    start, end = erase_range(base, image)
    if is_test:
        confirm(f"SIGNATURE TEST. This ERASES {start:#x}..{end:#x} and writes Razer's 2.2 with ONE byte changed (the USB\n"
                "product string). Possible outcomes:\n"
                "  - it boots as \"Razer Xishi V2 Pro\": the bootloader checks no signature (custom firmware possible);\n"
                "  - the bootloader rejects it, or it does not boot: restore with the official image (flash <2.2.hex>).\n"
                "Do not unplug until it finishes.", args.yes)
    elif kind == "custom":
        confirm(f"KISHIDS FIRMWARE. This ERASES {start:#x}..{end:#x} (Razer's application) and writes our build. Ways back\n"
                "to Razer's bootloader if it misbehaves:\n"
                "  - it works (shows up as a DS4): v2pro_dfu.py enter (KishiDS live command 7);\n"
                "  - it faults: it restarts into the bootloader by itself;\n"
                "  - no USB: unplug, hold View + Menu, plug back in.\n"
                "Then: v2pro_dfu.py flash <Razer 2.2 .hex> restores Razer's firmware. Do not unplug until it finishes.",
                args.yes)
    else:
        confirm(f"This ERASES {start:#x}..{end:#x} and writes Razer's firmware back. Do not unplug until it finishes.",
                args.yes)
    erased = False
    echo_warned = False
    try:
        r = expect(link.command(DFU, DFU_ERASE, erase_data(start, end), timeout=30.0), DFU_ERASE, "DFUErase")
        erased = True
        # ASSUMPTION: replies echo the request layout. Razer's updater checks the echo, but a wrong guess here must not
        # abort a half-written flash, so a mismatch is only reported; DFUVerify's read-back is the real check.
        if r.data[:8] != erase_data(start, end):
            print(f"warning: DFUErase echo differs from the request: {r.data[:8].hex(' ')}")
        print("Erased.")
        for off in range(0, len(image), BLOCK):
            chunk = image[off : off + BLOCK]
            sent = program_data(base + off, chunk)
            r = expect(link.command(DFU, DFU_PROGRAM, sent, timeout=2.0), DFU_PROGRAM, f"DFUProgram {base + off:#x}")
            if r.data[1 : 5 + len(chunk)] != sent[1:] and not echo_warned:
                echo_warned = True
                print(f"\nwarning: DFUProgram echo differs from the request (reported once): {r.data[:16].hex(' ')}")
            print(f"\rProgram {off + len(chunk)}/{len(image)}", end="", flush=True)
        print()
        for off in range(0, len(image), BLOCK):
            chunk = image[off : off + BLOCK]
            data, size = verify_data(base + off, len(chunk))
            r = expect(link.command(DFU, DFU_VERIFY, data, size, timeout=2.0), DFU_VERIFY, f"DFUVerify {base + off:#x}")
            if r.data[5 : 5 + len(chunk)] != chunk:
                # ASSUMPTION: the read-back follows the 5-byte header, as in the request.
                raise RuntimeError(f"DFUVerify {base + off:#x}: read-back differs from the image: {r.data[:16].hex(' ')}")
            print(f"\rVerify {off + len(chunk)}/{len(image)}", end="", flush=True)
        print()
    except (RuntimeError, OSError) as exc:
        print(f"\nFAILED: {exc}")
        if erased:
            try:
                print(f"DFUAbort: {link.command(DFU, DFU_ABORT, timeout=2.0)}")
            except OSError:
                pass
            print("The application area may be incomplete. The controller stays in the bootloader; run flash again.\n"
                  "If only DFUVerify failed, the layout guess may be wrong rather than the flash: try v2pro_dfu.py exit.")
        link.close()
        raise SystemExit(1)
    try:
        print(f"DFUExit: {link.command(DFU, DFU_EXIT, timeout=2.0)}")
    except OSError as exc:
        print(f"DFUExit: no reply ({exc}); the controller may already be restarting.")
    finally:
        link.close()
    if kind == "custom":
        report_custom_boot(60)   # a diagnostic build waits up to ~45 s before re-entering the bootloader (timing channel)
        return
    found = wait_report(rz.V2_PRO_PIDS, 15, "the Kishi V2 Pro application")
    if found and is_test:
        product = found.get("product_string") or ""
        print(f"USB product string: {product!r}")
        if "Xishi" in product:
            print("RESULT: the modified image booted. The bootloader does not check a signature.")
        else:
            print("RESULT: unclear (the name did not change; it may be cached by the OS). Check v2pro_probe.py list, then\n"
                  "restore the official image either way.")
    elif found:
        print("Restored. Check with: python tools/v2pro_probe.py info")
    elif is_test:
        print("RESULT: the modified image did not come up as the application. If the bootloader is still there, the image\n"
              "was most likely rejected. Restore: python tools/v2pro_dfu.py flash <official 2.2 .hex>")
    else:
        print("The application did not enumerate yet. Unplug and replug, then run v2pro_probe.py list.")


if __name__ == "__main__":
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest="cmd", required=True)
    for name in ("plan", "flash"):
        p = sub.add_parser(name)
        p.add_argument("image", type=Path)
    sub.choices["flash"].add_argument("--signature-test", action="store_true",
                                      help="allow the one-byte signature-test image made by `sigtest`")
    sub.choices["flash"].add_argument("--custom", action="store_true",
                                      help="allow a KishiDS V2 Pro build (v2pro-firmware/kishi_v2pro_ds4.hex) that passes "
                                           "the built-in checks")
    p = sub.add_parser("sigtest")
    p.add_argument("image", type=Path, help="Razer's official 2.2 .hex")
    p.add_argument("out", type=Path, help="where to write the test image")
    sub.add_parser("enter")
    sub.add_parser("exit")
    sub.add_parser("readdiag")
    for p in sub.choices.values():
        p.add_argument("--tid", type=lambda s: int(s, 0), default=None, help="transaction ID (default: try 0x1F, 0x3F, 0xFF)")
        p.add_argument("--yes", action="store_true", help="do not ask for confirmation")
    args = ap.parse_args()
    {"plan": cmd_plan, "enter": cmd_enter, "exit": cmd_exit, "flash": cmd_flash, "sigtest": cmd_sigtest,
     "readdiag": cmd_readdiag}[args.cmd](args)
