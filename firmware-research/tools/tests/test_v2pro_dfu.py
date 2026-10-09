"""Exercise v2pro_dfu.py against a simulated Kishi V2 Pro and Razer bootloader (no hardware, no Razer files).

  python tools/tests/test_v2pro_dfu.py

The simulator implements the command layouts documented in ../../KISHI_V2_PRO.md, so it checks the tool's logic
(transaction-ID discovery, guards, flash/verify/abort flow), not the real bootloader's behaviour.
"""

from __future__ import annotations

import argparse
import contextlib
import hashlib
import io
import sys
import tempfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import razer_v25 as rz  # noqa: E402
import v2pro_dfu as dfu  # noqa: E402

DEVICE_TID = 0x3F  # deliberately not the first guess, to exercise discovery


class Controller:
    """Modes: "app" (Razer's firmware), "boot" (Razer's bootloader), "kishids" (our firmware, a DS4)."""

    def __init__(self, fail_program_at: int | None = None, exit_boots: bool = True):
        self.mode = "app"
        self.real_ds4 = False   # a Sony DS4 is plugged in too (it does not know the KishiDS live report)
        self.flash = bytearray(b"\x11" * 0x20000)
        self.fail_program_at = fail_program_at
        self.exit_boots = exit_boots
        self.log: list[tuple[str, int, int]] = []
        self.reply = b""

    def handle(self, p: bytes) -> None:
        assert len(p) == rz.REPORT_LEN and rz.xor(p[2:88]) == p[88], "bad request packet"
        tid, cls, cid, data = p[1], p[6], p[7], p[8:88]
        self.log.append((self.mode, cls, cid))
        out = bytearray(p)
        out[0] = rz.ST_OK
        if tid != DEVICE_TID:
            out[0] = 0x03
        elif self.mode == "app":
            if (cls, cid) == (0x00, 0x81):
                out[8:12] = bytes([2, 2, 0, 0])
            elif (cls, cid) == (0x00, 0x04):
                assert data[:2] == dfu.MODE_BOOTLOADER
                self.mode = "boot"
            else:
                out[0] = 0x05
        elif cls != dfu.DFU:
            out[0] = 0x05
        elif cid == dfu.DFU_INFO:
            out[8:18] = bytes(range(10))
        elif cid == dfu.DFU_ERASE:
            start, end = int.from_bytes(data[0:4], "big"), int.from_bytes(data[4:8], "big")
            assert start >= dfu.APP_BASE
            self.flash[start : end + 1] = b"\xff" * (end + 1 - start)
        elif cid == dfu.DFU_PROGRAM:
            n, addr = data[0], int.from_bytes(data[1:5], "big")
            if addr == self.fail_program_at:
                out[0] = 0x03
            else:
                self.flash[addr : addr + n] = data[5 : 5 + n]
        elif cid == dfu.DFU_VERIFY:
            n, addr = data[0], int.from_bytes(data[1:5], "big")
            out[13 : 13 + n] = self.flash[addr : addr + n]
        elif cid == dfu.DFU_EXIT and self.exit_boots:
            # Whatever is in the application area runs: ours if it carries the recovery marker.
            self.mode = "kishids" if dfu.RECOVERY_MARKER in self.flash else "app"
        out[88] = rz.xor(out[2:88])
        self.reply = bytes(out)


CTL = Controller()


class FakeHid:
    def open_path(self, path): pass
    def close(self): pass
    def send_feature_report(self, buf): CTL.handle(bytes(buf[1:]))
    def get_feature_report(self, rid, n): return [0] + list(CTL.reply)


class FakeDs4:
    """Our firmware's DS4 interface (KishiDS live report 0xAC), or a real DS4 that does not have it."""

    def __init__(self, real: bool):
        self.real = real

    def close(self): pass

    def get_feature_report(self, rid, n):
        if self.real or rid != dfu.LIVE_REPORT:
            raise OSError("feature report not supported")
        return [dfu.LIVE_REPORT, dfu.LIVE_PROTOCOL] + [0] * (n - 2)

    def send_feature_report(self, buf):
        buf = bytes(buf)
        assert len(buf) == dfu.LIVE_REPORT_LEN and not self.real, "live command sent to a real DS4"
        CTL.log.append(("kishids", buf[0], buf[2]))
        if buf[:3] == bytes([dfu.LIVE_REPORT, dfu.LIVE_SIGNATURE, dfu.LIVE_BOOTLOADER]):
            CTL.mode = "boot"


def fake_ds4_interfaces():
    found = [dict(path=b"real-ds4")] if CTL.real_ds4 else []
    return found + ([dict(path=b"kishids")] if CTL.mode == "kishids" else [])


def fake_interfaces(pids=tuple(rz.KNOWN_PIDS)):
    if CTL.mode in ("kishids", "none"):
        return []
    pid = rz.BOOTLOADER_PID if CTL.mode == "boot" else 0x0717
    if pid not in pids:
        return []
    return [dict(path=b"sim", product_id=pid, vendor_id=rz.RAZER_VID, interface_number=2, usage_page=0xFF00, usage=1)]


def fake_link_init(self, info, tid):
    self.info, self.tid, self.dev = info, tid, FakeHid()


rz.interfaces = fake_interfaces
rz.Link.__init__ = fake_link_init
rz.wait_for = lambda pids, seconds: (fake_interfaces(pids) or [None])[0]
dfu.ds4_interfaces = fake_ds4_interfaces
dfu.open_hid = lambda path: FakeDs4(real=path == b"real-ds4")


def write_hex(path: Path, base: int, data: bytes) -> None:
    lines, upper = [], None
    for off in range(0, len(data), 16):
        if (base + off) >> 16 != upper:   # extended linear address record at every 64 KB boundary
            upper = (base + off) >> 16
            rec = bytes([2, 0, 0, 4, upper >> 8, upper & 0xFF])
            lines.append(":" + (rec + bytes([(-sum(rec)) & 0xFF])).hex().upper())
        addr = (base + off) & 0xFFFF
        rec = bytes([16, addr >> 8, addr & 0xFF, 0]) + data[off : off + 16]
        lines.append(":" + (rec + bytes([(-sum(rec)) & 0xFF])).hex().upper())
    lines.append(":00000001FF")
    path.write_text("\n".join(lines) + "\n")


def kishids_image(base: int = 0x8000, size: int = 0x1000, marker: bool = True, stamped: bool = True) -> bytes:
    """A minimal image shaped like a KishiDS V2 Pro build: vectors, config block (CRC stamped), recovery marker."""
    import binascii
    import struct

    data = bytearray(b"\xff" * size)
    data[0:64] = bytes(64)   # unused core vectors are 0, as in the real build
    struct.pack_into("<II", data, 0, 0x04008000, base + 0x101)
    struct.pack_into("<I", data, 12, base + 0x105)   # HardFault
    data[0x100:0x110] = b"\x00\xbf" * 8
    if marker:
        data[0x200 : 0x200 + len(dfu.RECOVERY_MARKER)] = dfu.RECOVERY_MARKER
    cfg = 0x400
    data[cfg : cfg + 12] = dfu.CONFIG_MARKER
    data[cfg + 16 : cfg + 256] = bytes(i & 0xFF for i in range(240))
    if stamped:
        struct.pack_into("<I", data, cfg + 12, binascii.crc32(bytes(data[cfg + 16 : cfg + 256])) & 0xFFFFFFFF)
    return bytes(data)


def make_image(path: Path, base: int = 0x8000, size: int = 0x1000) -> bytes:
    image = bytes((i * 7 + 3) & 0xFF for i in range(size))
    lines = [f":02000004{base >> 16:04X}{(-(2 + 4 + (base >> 24) + ((base >> 16) & 0xFF))) & 0xFF:02X}"]
    for off in range(0, size, 16):
        addr = (base + off) & 0xFFFF
        rec = bytes([16, addr >> 8, addr & 0xFF, 0]) + image[off : off + 16]
        lines.append(":" + (rec + bytes([(-sum(rec)) & 0xFF])).hex().upper())
    lines.append(":00000001FF")
    path.write_text("\n".join(lines) + "\n")
    return image


def run(fn, image: Path) -> int:
    args = argparse.Namespace(tid=None, yes=True, image=image, signature_test=False)
    with contextlib.redirect_stdout(io.StringIO()):
        try:
            fn(args)
        except SystemExit as exc:
            return exc.code or 0
    return 0


def main() -> None:
    global CTL
    with tempfile.TemporaryDirectory() as tmp:
        hex_path = Path(tmp) / "image.hex"
        image = make_image(hex_path)
        base = dfu.APP_BASE

        # flash refuses anything not on the official list
        assert run(dfu.cmd_flash, hex_path) != 0
        dfu.OFFICIAL_IMAGES[hashlib.sha256(hex_path.read_bytes()).hexdigest()] = "test image"
        assert dfu.load_hex(hex_path) == (base, image)

        CTL = Controller()
        assert run(dfu.cmd_enter, hex_path) == 0 and CTL.mode == "boot"
        assert not any(cid in (dfu.DFU_ERASE, dfu.DFU_PROGRAM) for _, cls, cid in CTL.log if cls == dfu.DFU)
        assert run(dfu.cmd_exit, hex_path) == 0 and CTL.mode == "app"

        CTL = Controller(exit_boots=False)
        CTL.mode = "boot"
        assert run(dfu.cmd_exit, hex_path) == 0 and CTL.mode == "boot"

        CTL = Controller()
        CTL.mode = "boot"
        assert run(dfu.cmd_flash, hex_path) == 0 and CTL.mode == "app"
        assert bytes(CTL.flash[base : base + len(image)]) == image
        assert set(CTL.flash[:base]) == {0x11}, "touched the bootloader area"

        CTL = Controller(fail_program_at=base + 0x400)
        CTL.mode = "boot"
        assert run(dfu.cmd_flash, hex_path) == 1 and CTL.mode == "boot"
        assert CTL.log[-1] == ("boot", dfu.DFU, dfu.DFU_ABORT)

        # Signature-test rules: refused without --signature-test, accepted with it, never for an unknown file.
        test_path = Path(tmp) / "test.hex"
        data = bytearray(image)
        data[0] ^= 0xFF   # one byte changed, like the real signature-test image
        lines = [":020000040000FA"]
        for off in range(0, len(data), 16):
            addr = (base + off) & 0xFFFF
            rec = bytes([16, addr >> 8, addr & 0xFF, 0]) + bytes(data[off : off + 16])
            lines.append(":" + (rec + bytes([(-sum(rec)) & 0xFF])).hex().upper())
        lines.append(":00000001FF")
        test_path.write_text("\n".join(lines) + "\n")
        dfu.TEST_IMAGES[hashlib.sha256(test_path.read_bytes()).hexdigest()] = "test image (signature test)"
        CTL = Controller()
        CTL.mode = "boot"
        args = argparse.Namespace(tid=None, yes=True, image=test_path, signature_test=False)
        with contextlib.redirect_stdout(io.StringIO()):
            try:
                dfu.cmd_flash(args)
                refused = False
            except SystemExit:
                refused = True
        assert refused and not any(cid == dfu.DFU_ERASE for _, _, cid in CTL.log), "test image flashed without the flag"
        args.signature_test = True
        with contextlib.redirect_stdout(io.StringIO()):
            dfu.cmd_flash(args)
        assert bytes(CTL.flash[base : base + len(data)]) == bytes(data), "test image not written with the flag"
        unknown = Path(tmp) / "unknown.hex"
        unknown.write_text(test_path.read_text().replace(":00000001FF", ":00000001FF\n"))
        args.image = unknown
        try:
            with contextlib.redirect_stdout(io.StringIO()):
                dfu.cmd_flash(args)
            raise AssertionError("unknown image accepted with --signature-test")
        except SystemExit:
            pass

        # KishiDS builds: only with --custom, only when every check passes; then the whole round trip.
        def flash(path: Path, custom: bool) -> tuple[int, str]:
            out = io.StringIO()
            args = argparse.Namespace(tid=None, yes=True, image=path, signature_test=False, custom=custom)
            with contextlib.redirect_stdout(out):
                try:
                    dfu.cmd_flash(args)
                    code = 0
                except SystemExit as exc:   # a refusal carries its message as the code
                    code = 0 if exc.code in (None, 0) else 1
            return code, out.getvalue()

        ours = Path(tmp) / "kishi_v2pro_ds4.hex"
        good = kishids_image()
        write_hex(ours, base, good)
        CTL = Controller()
        CTL.mode = "boot"
        assert flash(ours, custom=False)[0] != 0, "KishiDS image accepted without --custom"
        assert not any(cid == dfu.DFU_ERASE for _, _, cid in CTL.log), "KishiDS image flashed without --custom"
        for label, bad_base, data in (("no recovery marker", base, kishids_image(marker=False)),
                                      ("CRC not stamped", base, kishids_image(stamped=False)),
                                      ("wrong base", 0x9000, kishids_image(base=0x9000)),
                                      ("past Razer's span", base, kishids_image(size=dfu.APP_LIMIT - base + 0x200))):
            bad_path = Path(tmp) / "bad.hex"
            write_hex(bad_path, bad_base, data)
            CTL = Controller()
            CTL.mode = "boot"
            code, out = flash(bad_path, custom=True)
            assert code != 0 and not any(cid == dfu.DFU_ERASE for _, _, cid in CTL.log), f"accepted: {label}"

        CTL = Controller()
        CTL.mode = "boot"
        code, out = flash(ours, custom=True)
        assert code == 0 and CTL.mode == "kishids", out
        assert bytes(CTL.flash[base : base + len(good)]) == good and set(CTL.flash[:base]) == {0x11}
        assert "RESULT: our firmware booted" in out, out

        # From our firmware back to the bootloader (live command 7), with a real DS4 plugged in too: never touched.
        CTL.real_ds4 = True
        assert run(dfu.cmd_enter, ours) == 0 and CTL.mode == "boot"
        assert ("kishids", dfu.LIVE_REPORT, dfu.LIVE_BOOTLOADER) in CTL.log
        code, out = flash(hex_path, custom=False)   # restore the "official" image
        assert code == 0 and CTL.mode == "app", out

        # Only a real DS4 connected: enter refuses.
        CTL = Controller()
        CTL.mode, CTL.real_ds4 = "none", True
        assert run(dfu.cmd_enter, ours) != 0 and CTL.mode == "none"

        # Outcomes after flashing ours: fell back to the bootloader / nothing enumerated.
        for mode, expect in (("boot", "back in Razer's bootloader"), ("none", "nothing enumerated")):
            CTL = Controller()
            CTL.mode = mode
            out = io.StringIO()
            with contextlib.redirect_stdout(out):
                dfu.report_custom_boot(seconds=0.3)
            assert expect in out.getvalue(), out.getvalue()

        # readdiag: read-only, decodes the record a diagnostic build leaves at 0x1E000.
        import struct
        CTL = Controller()
        CTL.mode = "boot"
        record = (dfu.DIAG_MAGIC + struct.pack("<5I", 0x7F, 1 << 6, 0, 1234, 0) + struct.pack("<6I", 0, 0, 0, 0, 0, 0)
                  + struct.pack("<32I", *range(32)))
        CTL.flash[dfu.DIAG_ADDR : dfu.DIAG_ADDR + 512] = record + b"\xff" * (512 - len(record))
        out = io.StringIO()
        with contextlib.redirect_stdout(out):
            dfu.cmd_readdiag(argparse.Namespace(tid=None))
        text = out.getvalue()
        assert "stages missing: USB interrupt, mounted, configured" in text and "ADC offset cal" in text, text
        assert "main loop passes: 1234" in text and "USB0 DEVCMDSTAT    0x00000000" in text, text
        assert "reported because: USB not configured" in text, text
        assert not any(cid in (dfu.DFU_ERASE, dfu.DFU_PROGRAM, dfu.DFU_EXIT) for _, _, cid in CTL.log), "readdiag wrote"
        fault = bytearray(CTL.flash[dfu.DIAG_ADDR : dfu.DIAG_ADDR + 512])
        struct.pack_into("<6I", fault, 28, 5, 0x8200, 0, 0x400A205C, 0x9ABC, 0x9001)
        assert "exception 5 (BusFault) at PC 0x00009abc" in dfu.decode_diag(bytes(fault))

        for bad in ((dfu.erase_data, (0x7000, 0x9000)), (dfu.program_data, (0x7FC0, b"x" * 64)),
                    (dfu.program_data, (base, b"x" * 76))):
            try:
                bad[0](*bad[1])
            except ValueError:
                continue
            raise AssertionError(f"guard did not refuse {bad}")
    print("v2pro_dfu: all simulated checks passed")


if __name__ == "__main__":
    main()
