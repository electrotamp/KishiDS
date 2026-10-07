"""Host-side reader for the Kishi DIAG firmware (VID 054C / PID 05C4).

  python tools/diag_read.py live [seconds]   # decoded buttons + raw ADC, prints on change
  python tools/diag_read.py sweep [seconds]  # track per-channel min/max/rest (move sticks/triggers)
  python tools/diag_read.py capture [seconds]  # exercise everything, then print a summary
  python tools/diag_read.py cal              # dump stock calibration page via feature 0xF1/0xB0

Read-only: only input reports and GET_FEATURE are used (no output/set reports).
"""

from __future__ import annotations

import struct
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent / "python"))
import hid  # noqa: E402

VID, PID = 0x054C, 0x05C4
BUTTONS = ["A", "B", "X", "Y", "UP", "DOWN", "LEFT", "RIGHT", "L1", "R1", "L3", "R3", "RFUNC", "HOME", "LFUNC"]
ADC = ["RX(ch1)", "RY(ch2)", "R2(ch3)", "LY(ch5)", "LX(ch6)", "L2(ch8)"]


def open_dev() -> hid.device:
    d = hid.device()
    d.open(VID, PID)
    return d


def decode(rep: bytes):
    if len(rep) < 27 or rep[0] != 0x01:
        return None
    raw = [struct.unpack_from("<H", rep, 13 + 2 * i)[0] for i in range(6)]
    mask = rep[25] | (rep[26] << 8)
    pressed = [n for i, n in enumerate(BUTTONS) if mask & (1 << i)]
    return raw, pressed, rep[1:5], rep[5], rep[6], rep[7], rep[8], rep[9]


def live(seconds: float) -> None:
    d = open_dev()
    t0 = time.time()
    end = t0 + seconds
    last = None
    while time.time() < end:
        rep = bytes(d.read(64, 200))
        got = decode(rep) if rep else None
        if not got:
            continue
        raw, pressed, sticks, b5, b6, b7, l2, r2 = got
        line = f"raw={raw} btn={pressed} sticks={list(sticks)} hat={b5 & 15} L2={l2} R2={r2}"
        key = (tuple(pressed), b5 & 15, l2 // 32, r2 // 32, tuple(x // 32 for x in sticks))
        if key != last:
            print(f"t={time.time() - t0:5.1f}s {line}")
            last = key


def sweep(seconds: float) -> None:
    d = open_dev()
    lo = [4095] * 6
    hi = [0] * 6
    first = None
    end = time.time() + seconds
    while time.time() < end:
        rep = bytes(d.read(64, 200))
        got = decode(rep) if rep else None
        if not got:
            continue
        raw = got[0]
        if first is None:
            first = raw[:]
            print("rest (first sample):", dict(zip(ADC, first)))
        for i, v in enumerate(raw):
            lo[i] = min(lo[i], v)
            hi[i] = max(hi[i], v)
    print("channel     rest   min   max")
    for i, n in enumerate(ADC):
        print(f"{n:9s} {first[i]:6d} {lo[i]:5d} {hi[i]:5d}")


def capture(seconds: float) -> None:
    """Record for N seconds, then summarize everything that was exercised."""
    d = open_dev()
    seen_btn = {n: 0 for n in BUTTONS}
    ds4_bits = {}
    hats = set()
    lo = [255] * 4
    hi = [0] * 4
    tmax = [0, 0]
    rawlo = [4095] * 6
    rawhi = [0] * 6
    n = 0
    end = time.time() + seconds
    while time.time() < end:
        rep = bytes(d.read(64, 200))
        got = decode(rep) if rep else None
        if not got:
            continue
        n += 1
        raw, pressed, sticks, b5, b6, b7, l2, r2 = got
        for name in pressed:
            seen_btn[name] += 1
        hats.add(b5 & 15)
        for bit in range(4, 8):
            if b5 & (1 << bit):
                ds4_bits["b5.%d" % bit] = ds4_bits.get("b5.%d" % bit, 0) + 1
        for bit in range(8):
            if b6 & (1 << bit):
                ds4_bits["b6.%d" % bit] = ds4_bits.get("b6.%d" % bit, 0) + 1
        if b7 & 1:
            ds4_bits["PS"] = ds4_bits.get("PS", 0) + 1
        for i in range(4):
            lo[i] = min(lo[i], sticks[i])
            hi[i] = max(hi[i], sticks[i])
        tmax[0] = max(tmax[0], l2)
        tmax[1] = max(tmax[1], r2)
        for i, v in enumerate(raw):
            rawlo[i] = min(rawlo[i], v)
            rawhi[i] = max(rawhi[i], v)
    print(f"{n} reports in {seconds:.0f}s")
    print("Kishi buttons seen (sample counts):", {k: v for k, v in seen_btn.items() if v})
    print("never pressed:", [k for k, v in seen_btn.items() if not v])
    print("DS4 bits seen:", ds4_bits)
    print("hat values seen:", sorted(hats))
    for name, a, b in zip(["LX", "LY", "RX", "RY"], lo, hi):
        print(f"stick {name}: min {a:3d} max {b:3d}")
    print(f"triggers max: L2={tmax[0]} R2={tmax[1]}")
    for i, nme in enumerate(ADC):
        print(f"raw {nme:9s}: {rawlo[i]:5d}..{rawhi[i]:5d}")


def cal() -> None:
    d = open_dev()
    page = b""
    for rid in (0xF1, 0xB0):
        data = bytes(d.get_feature_report(rid, 64))
        print(f"feature {rid:#04x}: {len(data)} bytes")
        page += data[1:]
    for off in range(0, 0x60, 16):
        chunk = page[off : off + 16]
        print(f"{off:04x}: {chunk.hex(' ')}")
    u16 = [struct.unpack_from("<H", page, o)[0] for o in range(0, 0x40, 2)]
    print("u16 fields:", [f"{o*2:#04x}={v}" for o, v in enumerate(u16)])
    print("magic bytes 0x51..0x58:", page[0x51:0x59].hex(" "))


if __name__ == "__main__":
    cmd = sys.argv[1] if len(sys.argv) > 1 else "live"
    arg = float(sys.argv[2]) if len(sys.argv) > 2 else 15.0
    {"live": lambda: live(arg), "sweep": lambda: sweep(arg), "capture": lambda: capture(arg), "cal": cal}[cmd]()
