"""Run the real ARM firmware functions in an emulator and compare them with the host build.

  C:\\...\\Python312\\python.exe tests/emu_test.py        (needs `pip install unicorn`; build first)

Checks, using the firmware image kishi_ds4.bin / kishi_ds4.elf:
  1. kcfg_load() honours a patched config block (and falls back to defaults when the CRC is bad).
  2. report_build() on the ARM image gives byte-identical output to the host-compiled report.c for
     hundreds of random configurations and inputs.
"""

from __future__ import annotations

import binascii
import os
import random
import shutil
import struct
import subprocess
import sys
from pathlib import Path

from unicorn import UC_ARCH_ARM, UC_MODE_THUMB, Uc, UcError
from unicorn.arm_const import UC_ARM_REG_LR, UC_ARM_REG_PC, UC_ARM_REG_R0, UC_ARM_REG_R1, UC_ARM_REG_R2, UC_ARM_REG_R3, UC_ARM_REG_SP

HERE = Path(__file__).resolve().parent
FW = HERE.parent
sys.path.insert(0, str(FW.parent / "tools"))
import gen_config  # noqa: E402

BIN = FW / "kishi_ds4.bin"
ELF = FW / "kishi_ds4.elf"
def _find_nm() -> Path:
    found = shutil.which("arm-none-eabi-nm")
    if found:
        return Path(found)
    roots = [os.environ.get("DEVKITARM", "")] + [f"{d}:/devkitPro/devkitARM" for d in "CDEFG"]
    for r in roots:
        cand = Path(r) / "bin" / "arm-none-eabi-nm.exe"
        if r and cand.exists():
            return cand
    raise SystemExit("arm-none-eabi-nm not found: put devkitARM's bin folder on PATH or set DEVKITARM")


NM = _find_nm()
BASE = 0x08003000
RAM = 0x20000000
SENTINEL = 0x08002000


def symbols() -> dict[str, int]:
    out = subprocess.run([str(NM), str(ELF)], capture_output=True, text=True, check=True).stdout
    return {line.split()[2]: int(line.split()[0], 16) for line in out.splitlines() if len(line.split()) == 3}


class Arm:
    def __init__(self, image: bytes):
        self.uc = Uc(UC_ARCH_ARM, UC_MODE_THUMB)
        self.uc.mem_map(BASE, 0x10000)
        self.uc.mem_write(BASE, image)
        self.uc.mem_map(RAM, 0x4000)
        self.uc.mem_map(SENTINEL & ~0xFFF, 0x1000)

    def call(self, addr: int, args=(), stack=()) -> int:
        sp = RAM + 0x3F00
        if stack:
            sp -= 4 * len(stack)
            for i, v in enumerate(stack):
                self.uc.mem_write(sp + 4 * i, struct.pack("<I", v))
        for reg, v in zip((UC_ARM_REG_R0, UC_ARM_REG_R1, UC_ARM_REG_R2, UC_ARM_REG_R3), args):
            self.uc.reg_write(reg, v)
        self.uc.reg_write(UC_ARM_REG_SP, sp)
        self.uc.reg_write(UC_ARM_REG_LR, SENTINEL | 1)
        self.uc.emu_start(addr | 1, SENTINEL, count=2_000_000)
        return self.uc.reg_read(UC_ARM_REG_R0)


def field_bytes(f, value) -> bytes:
    size = {"u8": 1, "u16": 2, "u32": 4, "char": 1}[f["kind"]]
    if f["kind"] == "char":
        return str(value).encode().ljust(f["count"], b"\0")
    vals = value if isinstance(value, list) else [value]
    fmt = {1: "<B", 2: "<H", 4: "<I"}[size]
    return b"".join(struct.pack(fmt, v) for v in vals)


def build_block(overrides: dict | None = None, fix_crc: bool = True) -> bytearray:
    block = bytearray()
    for f in gen_config.F:
        value = (overrides or {}).get(f["name"], f["default"])
        block += field_bytes(f, value)
    assert len(block) == 256
    if fix_crc:
        struct.pack_into("<I", block, 12, binascii.crc32(bytes(block[16:])) & 0xFFFFFFFF)
    return block


def find_block(image: bytes) -> int:
    marker = b"KISHICFG" + struct.pack("<HH", gen_config.CONFIG_VERSION, gen_config.CONFIG_SIZE)
    i = image.find(marker)
    assert i >= 0 and image.find(marker, i + 1) < 0, "config block not unique"
    return i


def test_config_load(sym, image, results):
    at = find_block(image)
    # 1) A stock-built image loads its own (default) block.
    arm = Arm(image)
    arm.call(sym["kcfg_load"])
    kcfg = bytes(arm.uc.mem_read(sym["kcfg"], 256))
    from_image = arm.uc.mem_read(sym["kcfg_from_image"], 1)[0]
    results.append(("default image: block accepted", from_image == 1))
    results.append(("default image: kcfg == block", kcfg[16:] == image[at + 16 : at + 256]))

    # 2) Patched block is honoured (values chosen away from the defaults).
    over = {"button_map": [3, 2, 4, 1, 16, 15, 18, 17, 6, 5, 12, 11, 9, 7, 8, 0], "stick_flags": 0x15, "left_dead": 21,
            "left_curve": 2, "led_mode": 2, "led_brightness": 77, "poll_ms": 2,
            "product": "Test Pad", "calib_mode": 1}
    patched = bytearray(image)
    patched[at : at + 256] = build_block(over)
    arm = Arm(bytes(patched))
    arm.call(sym["kcfg_load"])
    kcfg = bytes(arm.uc.mem_read(sym["kcfg"], 256))
    exp = bytes(build_block(over))
    results.append(("patched image: from_image flag", arm.uc.mem_read(sym["kcfg_from_image"], 1)[0] == 1))
    results.append(("patched image: all 240 settings bytes honoured", kcfg[16:] == exp[16:]))
    crc = struct.unpack("<I", arm.uc.mem_read(sym["kcfg_active_crc"], 4))[0]
    results.append(("patched image: active CRC matches", crc == binascii.crc32(exp[16:]) & 0xFFFFFFFF))

    # 2b) Locked USB identity is forced back to the DS4 values even in a patched block.
    forced = {"vid": 0x1234, "pid": 0xABCD, "bcd_device": 0x0999, "manufacturer": "Evil Corp", "serial": "Spoofed"}
    patched = bytearray(image)
    patched[at : at + 256] = build_block(forced)
    arm = Arm(bytes(patched))
    arm.call(sym["kcfg_load"])
    k = bytes(arm.uc.mem_read(sym["kcfg"], 256))
    results.append(("locked identity forced to DS4 values", struct.unpack_from("<HHH", k, 88) == (0x054C, 0x05C4, 0x0100)
                    and k[96:128].rstrip(b"\0") == b"ElectroTamp KishiDS" and k[160:192] == bytes(32)))

    # 3) Bad CRC -> defaults, flag cleared.
    bad = bytearray(image)
    bad[at : at + 256] = build_block(over, fix_crc=False)
    arm = Arm(bytes(bad))
    arm.call(sym["kcfg_load"])
    kcfg = bytes(arm.uc.mem_read(sym["kcfg"], 256))
    dflt = bytes(build_block())
    results.append(("bad CRC: falls back to defaults", kcfg[16:] == dflt[16:] and arm.uc.mem_read(sym["kcfg_from_image"], 1)[0] == 0))

    # 4) Out-of-range values are clamped.
    wild = {"left_dead": 200, "led_mode": 77, "poll_ms": 0, "left_outer": 3, "button_map": [250] * 16}
    patched = bytearray(image)
    patched[at : at + 256] = build_block(wild)
    arm = Arm(bytes(patched))
    arm.call(sym["kcfg_load"])
    k = bytes(arm.uc.mem_read(sym["kcfg"], 256))
    ok = k[33] == 50 and k[47] == 3 and k[50] == 1 and k[35] == 50 and all(b == 18 for b in k[16:32])
    results.append(("out-of-range values clamped", ok))


def test_serial(sym, image, results):
    """kcfg_resolve_serial on the ARM image: the factory field wins, the chip ID is the fallback."""
    OUT, FAC, UID = RAM + 0x2000, RAM + 0x2100, RAM + 0x2200
    uid = (0x12345678, 0x9ABCDEF0, 0x0F1E2D3C)

    def run(factory: bytes) -> bytes:
        arm = Arm(image)
        arm.uc.mem_write(FAC, factory)
        arm.uc.mem_write(UID, struct.pack("<3I", *uid))
        arm.uc.mem_write(OUT, b"\xAA" * 32)
        arm.call(sym["kcfg_resolve_serial"], (OUT, FAC, UID))
        return bytes(arm.uc.mem_read(OUT, 32))

    results.append(("serial: factory field used (ARM)", run(b"SAMPLE000000001\0") == b"SAMPLE000000001".ljust(32, b"\0")))
    results.append(("serial: erased field -> chip ID (ARM)", run(b"\xFF" * 16) == b"123456789ABCDEF00F1E2D3C".ljust(32, b"\0")))
    results.append(("serial: unterminated field -> chip ID (ARM)", run(b"0123456789ABCDEF") == b"123456789ABCDEF00F1E2D3C".ljust(32, b"\0")))


def test_live_protocol(sym, image, results):
    """Run the real ARM live-editing code: stage/apply/read-back and the saved-record boot path."""
    PKT, REP = RAM + 0x2000, RAM + 0x2200

    def send(arm, cmd, idx=0, data=b""):
        pkt = bytes([0xAC, 0x4B, cmd, idx]) + data.ljust(60, bytes(1))
        arm.uc.mem_write(PKT, pkt)
        arm.call(sym["live_set_report"], (PKT, len(pkt)))

    def get(arm):
        arm.call(sym["live_get_report"], (REP,))
        return bytes(arm.uc.mem_read(REP, 58))

    over = {"led_mode": 2, "led_brightness": 91, "left_dead": 17, "button_map": [3, 2, 4, 1, 16, 15, 18, 17, 6, 5, 12, 11, 9, 7, 8, 0]}
    block = bytes(build_block(over))
    arm = Arm(image)
    arm.call(sym["kcfg_load"])
    arm.call(sym["live_init"])
    base_crc = struct.unpack("<I", arm.uc.mem_read(sym["kcfg_active_crc"], 4))[0]
    for i in range(8):
        send(arm, 1, i, block[32 * i : 32 * i + 32])
    send(arm, 2)
    rep = get(arm)
    kcfg = bytes(arm.uc.mem_read(sym["kcfg"], 256))
    want_crc = binascii.crc32(block[16:]) & 0xFFFFFFFF
    results.append(("live: apply accepted, status ok", rep[0] == 0xAC and rep[2] == 0 and rep[3] == 2))
    results.append(("live: active config == applied block", kcfg == block))
    results.append(("live: reported crcs", struct.unpack_from("<II", rep, 4) == (want_crc, base_crc) and rep[12] & 1))
    chunks = b""
    for i in range(8):
        send(arm, 3, i)
        chunks += get(arm)[16:48]
    results.append(("live: read-back over 8 chunks == block", chunks == block))

    # Corrupt block rejected, config untouched.
    bad = bytearray(block); bad[40] ^= 0xFF
    for i in range(8):
        send(arm, 1, i, bytes(bad[32 * i : 32 * i + 32]))
    send(arm, 2)
    results.append(("live: corrupt block rejected", get(arm)[2] == 1 and bytes(arm.uc.mem_read(sym["kcfg"], 256)) == block))

    # Saved record in (emulated) flash wins at boot only with the matching image_id.
    probe = Arm(image)
    probe.call(sym["kcfg_load"])
    image_id = struct.unpack("<I", probe.uc.mem_read(sym["kcfg_image_id"], 4))[0]
    for iid, expect in ((image_id, True), (image_id ^ 1, False)):
        a = Arm(image)
        a.uc.mem_write(0x0800F000, block + struct.pack("<II", iid, 0x5AFE5AFE))
        a.call(sym["kcfg_load"])
        loaded = bytes(a.uc.mem_read(sym["kcfg"], 256)) == block
        flag = a.uc.mem_read(sym["kcfg_from_saved"], 1)[0] == 1
        results.append((f"saved record {'matching' if expect else 'stale'} image_id -> {'used' if expect else 'ignored'}", loaded == expect and flag == expect))


def rand_config(rng: random.Random) -> dict:
    over = {}
    for f in gen_config.F:
        if f["lo"] is None or f["name"].startswith("cal_"):
            continue
        if f["count"] == 1:
            over[f["name"]] = rng.randint(f["lo"], f["hi"])
        else:
            over[f["name"]] = [rng.randint(f["lo"], f["hi"]) for _ in range(f["count"])]
    # plausible calibration with a little spread
    over["cal_smin"] = [rng.randint(400, 900) for _ in range(4)]
    over["cal_smax"] = [rng.randint(3000, 3700) for _ in range(4)]
    over["cal_scenter"] = [rng.randint(1700, 2300) for _ in range(4)]
    over["cal_t_lo"] = [rng.randint(400, 800) for _ in range(2)]
    over["cal_t_hi"] = [rng.randint(1700, 2100) for _ in range(2)]
    return over


def test_report_differential(sym, image, results, n=400):
    rng = random.Random(1234)
    host = FW / "tests" / "report_cli.exe"
    subprocess.run(["gcc", "-std=c11", "-Wall", "-Wextra", "-I", str(FW), str(FW / "tests/report_cli.c"), str(FW / "report.c"), str(FW / "config.c"),
                    "-o", str(host)], check=True)
    arm = Arm(image)
    cases, lines = [], []
    for _ in range(n):
        block = build_block(rand_config(rng))
        buttons = rng.getrandbits(15)
        adc = [rng.randint(0, 4095) for _ in range(6)]
        counter = rng.randint(0, 63)
        cases.append((block, buttons, adc, counter))
        lines.append(f"{block.hex()} {buttons:x} {' '.join(map(str, adc))} {counter}")
    ref = subprocess.run([str(host)], input="\n".join(lines) + "\n", capture_output=True, text=True, check=True).stdout.split()
    mismatches = 0
    CFG, CAL, ADC, OUT = RAM + 0x2000, RAM + 0x2200, RAM + 0x2300, RAM + 0x2400
    for (block, buttons, adc, counter), want in zip(cases, ref):
        arm.uc.mem_write(CFG, bytes(block))
        arm.call(sym["report_cal_from_config"], (CAL, CFG))
        arm.uc.mem_write(ADC, struct.pack("<6H", *adc))
        arm.call(sym["report_build"], (CFG, CAL, buttons, ADC), (counter, OUT))
        got = bytes(arm.uc.mem_read(OUT, 64)).hex()
        if got != want:
            mismatches += 1
            if mismatches <= 3:
                print("MISMATCH\n host:", want, "\n  arm:", got, "\n  input:", buttons, adc, counter)
    results.append((f"report_build ARM == host on {n} random cases", mismatches == 0))


def main() -> int:
    sym = symbols()
    image = BIN.read_bytes()
    results: list[tuple[str, bool]] = []
    try:
        test_config_load(sym, image, results)
        test_serial(sym, image, results)
        test_live_protocol(sym, image, results)
        test_report_differential(sym, image, results)
    except UcError as e:
        print("emulation error:", e)
        return 2
    width = max(len(n) for n, _ in results)
    for name, ok in results:
        print(f"{'PASS' if ok else 'FAIL'}  {name:{width}s}")
    return 0 if all(ok for _, ok in results) else 1


if __name__ == "__main__":
    sys.exit(main())
