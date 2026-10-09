"""Razer "Protocol 2.5" over HID feature reports, as used by the Kishi V2 / V2 Pro and their bootloader.

Shared by v2pro_probe.py (read-only) and v2pro_dfu.py (bootloader). Command bytes and layouts come from Razer's
libRazer_IoT_SDK.so (Cortex Mobile 6.0.1); see ../KISHI_V2_PRO.md.

Packet (90 bytes, report ID 0), OpenRazer's razer_report:
  [0] status  [1] transaction ID  [2:4] remaining packets (BE)  [4] protocol type  [5] data size
  [6] command class  [7] command ID  [8:88] data  [88] XOR of bytes 2..87  [89] reserved
A command is a SET_FEATURE with the request, then GET_FEATURE until the status is no longer "busy".
"""

from __future__ import annotations

import time
from functools import reduce

RAZER_VID = 0x1532
KNOWN_PIDS = {
    0x0717: "Kishi V2 Pro (HID mode)",
    0x0718: "Kishi V2 Pro (XInput+ mode)",
    0x0712: "Kishi V2",
    0x071B: "Kishi V2 (new)",
    0x110E: "Razer bootloader (Recovery)",
}
V2_PRO_PIDS = (0x0717, 0x0718)
BOOTLOADER_PID = 0x110E

REPORT_LEN = 90
DATA_MAX = 80
TIDS = (0x1F, 0x3F, 0xFF)  # transaction IDs seen on Razer devices; the V2 Pro's is not known yet

ST_BUSY, ST_OK = 0x01, 0x02
STATUS = {0x00: "new", 0x01: "busy", 0x02: "ok", 0x03: "failed", 0x04: "timeout", 0x05: "not supported"}


def xor(buf: bytes) -> int:
    return reduce(lambda a, b: a ^ b, buf, 0)


def packet(tid: int, cls: int, cid: int, data: bytes = b"", size: int | None = None) -> bytes:
    if len(data) > DATA_MAX:
        raise ValueError(f"{len(data)} data bytes do not fit one packet")
    pkt = bytearray(REPORT_LEN)
    pkt[1] = tid
    pkt[5] = len(data) if size is None else size
    pkt[6] = cls
    pkt[7] = cid
    pkt[8 : 8 + len(data)] = data
    pkt[88] = xor(pkt[2:88])
    return bytes(pkt)


class Reply:
    def __init__(self, raw: bytes):
        self.raw = raw
        self.status = raw[0]
        self.cls, self.cid = raw[6], raw[7]
        self.data = raw[8 : 8 + min(raw[5], DATA_MAX)]
        self.crc_ok = xor(raw[2:88]) == raw[88]

    @property
    def ok(self) -> bool:
        return self.status == ST_OK

    def __str__(self) -> str:
        return (f"status={STATUS.get(self.status, hex(self.status))} crc={'ok' if self.crc_ok else 'BAD'} "
                f"cmd={self.cls:#04x}/{self.cid:#04x} data={self.data.hex(' ')}")


def read_reply(dev, timeout: float = 1.0) -> Reply | None:
    """GET_FEATURE until the device stops answering "busy" (or the timeout passes)."""
    end = time.monotonic() + timeout
    while True:
        time.sleep(0.01)
        raw = bytes(dev.get_feature_report(0, REPORT_LEN + 1))
        raw = raw[1:] if len(raw) == REPORT_LEN + 1 else raw
        if len(raw) < REPORT_LEN:
            return None
        if raw[0] != ST_BUSY or time.monotonic() > end:
            return Reply(raw)


def interfaces(pids=tuple(KNOWN_PIDS)) -> list[dict]:
    import hid

    return [d for d in hid.enumerate(RAZER_VID, 0) if d["product_id"] in pids]


class Link:
    """One open HID interface plus the transaction ID it answers to."""

    def __init__(self, info: dict, tid: int):
        import hid

        self.info, self.tid = info, tid
        self.dev = hid.device()
        self.dev.open_path(info["path"])

    @property
    def pid(self) -> int:
        return self.info["product_id"]

    def close(self) -> None:
        self.dev.close()

    def send(self, cls: int, cid: int, data: bytes = b"", size: int | None = None) -> None:
        self.dev.send_feature_report(b"\x00" + packet(self.tid, cls, cid, data, size))

    def receive(self, timeout: float = 1.0) -> Reply | None:
        return read_reply(self.dev, timeout)

    def command(self, cls: int, cid: int, data: bytes = b"", size: int | None = None, timeout: float = 1.0) -> Reply | None:
        self.send(cls, cid, data, size)
        return self.receive(timeout)


def open_link(pids, probe, tids=TIDS) -> Link | None:
    """Open the first interface of `pids` that answers `probe(link)` truthfully with one of `tids`.

    `probe` must only send read commands: it is how we find the right interface and transaction ID.
    """
    for info in interfaces(pids):
        for tid in tids:
            try:
                link = Link(info, tid)
            except OSError:
                break
            try:
                if probe(link):
                    return link
            except OSError:
                pass
            link.close()
    return None


def wait_for(pids, seconds: float) -> dict | None:
    end = time.monotonic() + seconds
    while time.monotonic() < end:
        found = interfaces(pids)
        if found:
            return found[0]
        time.sleep(0.25)
    return None
