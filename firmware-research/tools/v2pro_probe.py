"""Read-only probe for the Razer Kishi V2 Pro over Razer's "Protocol 2.5" feature reports.

  python tools/v2pro_probe.py list                 # list Razer HID interfaces (V2 Pro, Kishi V2, bootloader)
  python tools/v2pro_probe.py info [--tid 0x1F]    # firmware, serial, protocol version, device mode, edition
  python tools/v2pro_probe.py dfuinfo              # bootloader only (1532:110E): GetDFUDeviceInformation
  python tools/v2pro_probe.py buttons [seconds]    # live: which pin/scanner index each pressed button is, plus axes
  python tools/v2pro_probe.py raw [seconds]        # KishiDS firmware only: raw ADC per pin (telemetry report 0xAB)
  python tools/v2pro_probe.py boottime             # KishiDS firmware only: where its start-up time went (+ rumble status)
  python tools/v2pro_probe.py rumble               # KishiDS firmware only: strong, weak, both motors 2 s each
  python tools/v2pro_probe.py usblog               # KishiDS firmware only: what the last hosts did (USB flight log)
  python tools/v2pro_probe.py dry-run              # print the request packets without touching USB

Each query is a SET_FEATURE carrying the request followed by a GET_FEATURE for the reply, as Razer's app does.
Only "get" commands are allowed: in this protocol bit 7 of the command ID marks a read, and the packet builder
refuses any ID without it, so this tool cannot switch modes, write settings or flash (that is v2pro_dfu.py).

Protocol details: razer_v25.py and ../KISHI_V2_PRO.md.
"""

from __future__ import annotations

import argparse
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent / "python"))
sys.path.insert(0, str(Path(__file__).resolve().parent))
import razer_v25 as rz  # noqa: E402

# name: (class, id, data size). General-table sizes are the request buffers the Razer app passes down;
# the DFU size is what the native updater itself sends (0x50), not the 10-byte buffer on the Java side.
QUERIES = {
    "GetFirmwareVersion": (0x00, 0x81, 4),
    "GetSerialNumber": (0x00, 0x82, 16),
    "GetProtocolVersion": (0x00, 0x83, 2),
    "GetDeviceMode": (0x00, 0x84, 2),
    "GetEditionInformation": (0x00, 0x86, 3),
}
DFU_QUERIES = {
    "GetDFUDeviceInformation": (0x10, 0x80, 0x50),
}

# Razer factory-diagnostic read (class 0xFE, ID 0xB0), handled at 0x18d54 in firmware 2.2. Reply data:
# [0:4] stick axes and [4:6] triggers (copied from the HID input report), [6:9] the pressed buttons below.
DIAG_BUTTONS = (0xFE, 0xB0, 9)
# Reply bit (data[6:9] little-endian) -> (scanner index, LPC55 pin). From emulating the 2.2 image; see ../KISHI_V2_PRO.md.
DIAG_BITS = {
    0: (4, "P1_7"), 1: (7, "P0_22"), 2: (5, "P0_24"), 3: (6, "P1_17"), 4: (15, "P0_4"), 5: (16, "P0_6"),
    6: (17, "P0_8"), 7: (14, "P0_9"), 8: (2, "P0_0"), 9: (12, "P0_17"), 12: (3, "P1_14"), 13: (13, "P0_18"),
    14: (0, "P0_27"), 15: (10, "P0_31"), 16: (8, "P1_26"), 17: (18, "P0_19"), 18: (19, "P1_6"), 19: (9, "P1_5"),
}


def build(tid: int, cls: int, cid: int, size: int) -> bytes:
    if not cid & 0x80:
        raise ValueError(f"refusing command {cls:#04x}/{cid:#04x}: not a read (bit 7 clear)")
    return rz.packet(tid, cls, cid, size=size)


def query(dev, tid: int, name: str, spec: tuple[int, int, int]) -> bytes | None:
    cls, cid, size = spec
    dev.send_feature_report(b"\x00" + build(tid, cls, cid, size))
    reply = rz.read_reply(dev, timeout=0.5)
    if reply is None:
        print(f"  {name}: short reply")
        return None
    echo = (reply.cls, reply.cid) == (cls, cid)
    print(f"  {name}: {reply} echo={'ok' if echo else 'MISMATCH'}")
    return reply.data if reply.ok else None


def describe(name: str, data: bytes) -> str:
    if name == "GetSerialNumber":
        return data.split(b"\0")[0].decode("ascii", "replace")
    if name == "GetFirmwareVersion" and len(data) >= 2:
        return ".".join(str(b) for b in data)
    return data.hex(" ")


def run(queries: dict, tid: int, want_bootloader: bool) -> None:
    import hid

    found = [d for d in rz.interfaces() if (d["product_id"] == rz.BOOTLOADER_PID) == want_bootloader]
    if not found:
        what = "the Razer bootloader (1532:110E)" if want_bootloader else "a Kishi V2 / V2 Pro"
        raise SystemExit(f"No HID interface found for {what}. Try: python tools/v2pro_probe.py list")
    # The Razer interface is not always the first one; try each until one answers.
    for info in found:
        print(f"{rz.KNOWN_PIDS[info['product_id']]}  interface {info['interface_number']}  "
              f"usage {info['usage_page']:#06x}/{info['usage']:#06x}")
        dev = hid.device()
        try:
            dev.open_path(info["path"])
        except OSError as exc:
            print(f"  cannot open: {exc}")
            continue
        try:
            answered = {}
            for name, spec in queries.items():
                try:
                    data = query(dev, tid, name, spec)
                except OSError as exc:
                    print(f"  {name}: {exc}")
                    break
                if data is not None:
                    answered[name] = data
            if answered:
                print("Decoded:")
                for name, data in answered.items():
                    print(f"  {name:22s} {describe(name, data)}")
                return
        finally:
            dev.close()
    print("No interface answered. Try another transaction ID (--tid 0xFF, 0x3F, 0x1F).")


def buttons(tid: int, seconds: float) -> None:
    """Poll the diagnostic read and print every change: press each button once to map it."""
    import time

    import hid

    found = [d for d in rz.interfaces(rz.V2_PRO_PIDS)]
    for info in found:
        dev = hid.device()
        try:
            dev.open_path(info["path"])
            data = query(dev, tid, "DiagButtons", DIAG_BUTTONS)
        except OSError:
            dev.close()
            continue
        if data is None:
            dev.close()
            continue
        print(f"Interface {info['interface_number']} answers. Press one button at a time for {seconds:.0f} s.")
        last = None
        end = time.monotonic() + seconds
        try:
            while time.monotonic() < end:
                dev.send_feature_report(b"\x00" + build(tid, *DIAG_BUTTONS))
                reply = rz.read_reply(dev, timeout=0.3)
                if reply and reply.ok:
                    bits = int.from_bytes(reply.data[6:9], "little")
                    pressed = [f"bit{b}=idx{DIAG_BITS[b][0]}/{DIAG_BITS[b][1]}" if b in DIAG_BITS else f"bit{b}=?"
                               for b in range(24) if bits >> b & 1]
                    state = (tuple(pressed), tuple(v // 32 for v in reply.data[:6]))
                    if state != last:
                        print(f"axes={list(reply.data[:4])} triggers={list(reply.data[4:6])} pressed={pressed or '-'}")
                        last = state
                time.sleep(0.05)
        finally:
            dev.close()
        return
    raise SystemExit("No Kishi V2 Pro interface answered the diagnostic read. Try --tid 0x3F / 0xFF.")


# KishiDS firmware telemetry (feature report 0xAB, v2pro-firmware/usb_ds4.c fill_telemetry): [1] version 1,
# [3:5] Kishi button bits, [5:17] six raw 12-bit ADC values in the shared order below (board_v2pro.c names the pins).
RAW_ORDER = ("RX P0_16", "RY P0_23", "R2 P0_15", "LY P1_0", "LX P1_8", "L2 P0_10")


def raw(seconds: float) -> None:
    """Our firmware only: print the raw stick/trigger ADC values whenever one moves, to confirm the axis table."""
    import time

    import hid

    for info in hid.enumerate(0x054C, 0x05C4):
        dev = hid.device()
        try:
            dev.open_path(info["path"])
            t = bytes(dev.get_feature_report(0xAB, 58))
        except OSError:
            dev.close()
            continue
        if len(t) < 17 or t[0] != 0xAB or t[1] != 1:
            dev.close()
            continue
        print(f"KishiDS firmware answers (DS4 054C:05C4). Move one axis at a time for {seconds:.0f} s.\n"
              f"columns: {', '.join(RAW_ORDER)} (12-bit raw), then the Kishi button bits")
        last = None
        end = time.monotonic() + seconds
        try:
            while time.monotonic() < end:
                t = bytes(dev.get_feature_report(0xAB, 58))
                adc = [t[5 + 2 * i] | t[6 + 2 * i] << 8 for i in range(6)]
                btn = t[3] | t[4] << 8
                if last is None or btn != last[1] or any(abs(a - b) > 96 for a, b in zip(adc, last[0])):
                    print(" ".join(f"{v:5d}" for v in adc) + f"   buttons {btn:#06x}")
                    last = (adc, btn)
                time.sleep(0.05)
        finally:
            dev.close()
        return
    raise SystemExit("No KishiDS firmware found (a DS4 054C:05C4 that answers telemetry report 0xAB).")


BOOT_STAGES = ("View+Menu check", "clock_init", "board_init (pins, ADC)", "config load", "USB init", "USB configured")
BOOT_WAITS = ("flash read mode", "AHBCLKDIV", "USB0CLKDIV", "PRESETCTRL set", "PRESETCTRL clear", "ADCCLKDIV",
              "ADC offset cal", "ADC gain cal", "ADC cal ready", "flash command", "SCTCLKDIV")


def boottime() -> None:
    """Our firmware only: where start-up time went (cycle stamps and timed-out waits from telemetry report 0xAB)."""
    import hid

    for info in hid.enumerate(0x054C, 0x05C4):
        dev = hid.device()
        try:
            dev.open_path(info["path"])
            t = bytes(dev.get_feature_report(0xAB, 58))
        except OSError:
            continue
        finally:
            dev.close()
        if len(t) < 54 or t[0] != 0xAB or t[1] != 1:
            continue
        timeouts = int.from_bytes(t[26:29], "little")   # [29]: button bits 16..23
        stamps = [int.from_bytes(t[30 + 4 * i : 34 + 4 * i], "little") for i in range(6)]
        print("Start-up of the KishiDS firmware (CPU at 12 MHz until clock_init, 48 MHz after):")
        prev_ms, prev_cyc = 0.0, 0
        for i, (name, cyc) in enumerate(zip(BOOT_STAGES, stamps)):
            if not cyc:
                print(f"  {name:24s} (no stamp)")
                continue
            mhz = 12 if i <= 1 else 48
            ms = prev_ms + (cyc - prev_cyc) / (mhz * 1000)
            print(f"  {name:24s} at {ms:8.1f} ms  (+{ms - prev_ms:7.1f} ms)")
            prev_ms, prev_cyc = ms, cyc
        print("  waits that timed out: " + (", ".join(n for i, n in enumerate(BOOT_WAITS) if timeouts >> i & 1) or "none"))
        if len(t) >= 58:
            def drv(s: int) -> str:
                return "no answer" if s == 0xFF else f"STATUS {s:#04x} (device ID {s >> 5})"
            print(f"Rumble: host levels weak {t[54]} / strong {t[55]}; DRV2605 FLEXCOMM1 {drv(t[56])}, FLEXCOMM4 {drv(t[57])}")
        return
    raise SystemExit("No KishiDS firmware found (a DS4 054C:05C4 that answers telemetry report 0xAB).")


def rumble() -> None:
    """Our firmware only: strong motor 2 s, weak motor 2 s, both 2 s (DS4 output report 0x05), then off."""
    import time

    import hid

    dev = hid.device()
    try:
        dev.open(0x054C, 0x05C4)
    except OSError:
        raise SystemExit("No DS4 (054C:05C4) connected.")
    try:
        for name, weak, strong in (("STRONG (left / big)", 0, 255), ("WEAK (right / small)", 255, 0), ("BOTH", 255, 255)):
            print(f"{name} for 2 s...", flush=True)
            dev.write(bytes([0x05, 0x01, 0x04, 0x00, weak, strong]) + bytes(26))
            time.sleep(2)
            dev.write(bytes([0x05, 0x01, 0x04, 0x00, 0, 0]) + bytes(26))
            time.sleep(1)
    finally:
        dev.write(bytes([0x05, 0x01, 0x04, 0x00, 0, 0]) + bytes(26))
        dev.close()
    print("off")


def usblog() -> None:
    """Our firmware only: the USB flight recorder's two slots (v2pro-firmware/usblog_v2pro.h), newest last."""
    import struct

    import hid

    dev = hid.device()
    try:
        dev.open(0x054C, 0x05C4)
    except OSError:
        raise SystemExit("No DS4 (054C:05C4) connected.")
    chunks = []
    try:
        for idx in range(8, 12):
            dev.send_feature_report(bytes([0xAC, 0x4B, 3, idx]) + bytes(54))   # READSEL
            r = bytes(dev.get_feature_report(0xAC, 58))
            if r[13] != idx:
                raise SystemExit(f"READSEL {idx} not taken (firmware without the USB log?)")
            chunks.append(r[16:48])
    finally:
        dev.close()
    slots = [chunks[0] + chunks[1], chunks[2] + chunks[3]]
    recs = []
    for n, s in enumerate(slots):
        magic, seq = struct.unpack_from("<II", s, 0)
        if magic != 0x474F4C4B:
            print(f"slot {n}: empty")
            continue
        recs.append((seq, n, s))
    for seq, n, s in sorted(recs):
        (boots, conf, n_dev, n_cfg, n_str, n_rep, n_get, n_set, n_out) = s[8:17]
        rgb, rumble, (n_mount, n_suspend) = s[17:20], s[20:22], s[22:24]
        str_mask, = struct.unpack_from("<H", s, 24)
        ms_conf, irqs = struct.unpack_from("<II", s, 28)
        gets = [f"{b:#04x}" for b in s[36:36 + min(n_get, 8)]]
        sets = [f"{b:#04x}" for b in s[44:44 + min(n_set, 4)]]
        print(f"session seq {seq} (slot {n}):\n"
              f"  starts since power-on {boots}, configured {'yes at ' + str(ms_conf) + ' ms' if conf else 'NO'}, "
              f"mount {n_mount}, suspend {n_suspend}, USB interrupts {irqs}\n"
              f"  descriptors: device {n_dev}, configuration {n_cfg}, string {n_str} "
              f"(indexes {[i for i in range(16) if str_mask >> i & 1]}), HID report {n_rep}\n"
              f"  feature GET {n_get} {gets}, SET {n_set} {sets}; output reports {n_out}, "
              f"last colour {tuple(rgb)}, rumble weak/strong {tuple(rumble)}")


def list_devices() -> None:
    found = rz.interfaces()
    if not found:
        print("No Kishi V2 / V2 Pro / Razer bootloader connected.")
    for d in found:
        print(f"{d['vendor_id']:04x}:{d['product_id']:04x}  {rz.KNOWN_PIDS[d['product_id']]}  "
              f"iface {d['interface_number']}  usage {d['usage_page']:#06x}/{d['usage']:#06x}  "
              f"{d['manufacturer_string']!r} {d['product_string']!r} serial={d['serial_number']!r}")


def dry_run(tid: int) -> None:
    for name, (cls, cid, size) in {**QUERIES, **DFU_QUERIES, "DiagButtons": DIAG_BUTTONS}.items():
        pkt = build(tid, cls, cid, size)
        print(f"{name:24s} {pkt[:9].hex(' ')} ... crc={pkt[88]:02x}")
    for cls, cid in ((0x00, 0x04), (0x10, 0x01)):
        try:
            build(tid, cls, cid, 2)
        except ValueError as exc:
            print(f"write guard: {exc}")


if __name__ == "__main__":
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("cmd", choices=["list", "info", "dfuinfo", "buttons", "raw", "boottime", "rumble", "usblog",
                                    "dry-run"])
    ap.add_argument("seconds", nargs="?", type=float, default=30.0, help="buttons / raw: how long to watch")
    ap.add_argument("--tid", type=lambda s: int(s, 0), default=0x1F, help="transaction ID (default 0x1F)")
    args = ap.parse_args()
    if args.cmd == "list":
        list_devices()
    elif args.cmd == "info":
        run(QUERIES, args.tid, want_bootloader=False)
    elif args.cmd == "dfuinfo":
        run(DFU_QUERIES, args.tid, want_bootloader=True)
    elif args.cmd == "buttons":
        buttons(args.tid, args.seconds)
    elif args.cmd == "raw":
        raw(args.seconds)
    elif args.cmd == "boottime":
        boottime()
    elif args.cmd == "rumble":
        rumble()
    elif args.cmd == "usblog":
        usblog()
    else:
        dry_run(args.tid)
