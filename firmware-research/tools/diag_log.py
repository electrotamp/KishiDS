"""Read the DIAG firmware's flash black-box boot log (feature reports 0xF1/0xB0/0xAC) and decode it.

  python tools/diag_log.py      # needs the DIAG build running and enumerated (VID 054C / PID 05C4)
"""

from __future__ import annotations

import struct
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent / "python"))
import hid  # noqa: E402

LABELS = [
    "entry magic (0xB007)", "RCC_CSR>>16 (reset cause)", "RCC_CR>>16 after clock", "RCC_CR2>>16 after clock",
    "after io init (0xA001)", "USB_CNTR after usb_setup", "host bus RESET seen (0xE5E7)", "main loop alive (0x600D)",
    "HID class request (0xC7C7)", "SET_CONFIGURATION (0xC0FF)", "USB_ISTR @~10s", "USB_DADDR @~10s", "USB_CNTR @~10s", "10s marker (0xD00D)",
    "cal_load done (0xCA11)", "1st usbd_poll returned (0x9011)", "ADC_CR at 1st start (|0x8000)", "ADC_ISR at EOC timeout (|0x8000)",
    "1st conversion done (0xAD0E)", "1st full ADC pass (0xAD0F)", "buttons read (0xB070)", "report built (0xB011)", "1st ep write returned (0xE9E9)",
    "loop #2 (0x1002)", "loop #10 (0x1010)", "loop #100 (0x1100)", "loop #1000 (0x1999)", "USB_ISTR at loop #10 (|0x4000)",
]


def csr_flags(v: int) -> str:
    # RCC_CSR bits 31..24 -> v holds bits 31..16
    names = {15: "LPWR", 14: "WWDG", 13: "IWDG", 12: "SFT", 11: "POR", 10: "PIN", 9: "OBL"}
    return ",".join(n for b, n in names.items() if v & (1 << b)) or "none"


def main() -> None:
    d = hid.device()
    d.open(0x054C, 0x05C4)
    slots = []
    for rid in (0xF1, 0xB0, 0xAC):
        data = bytes(d.get_feature_report(rid, 64))[1:]
        hw = struct.unpack_from("<28H", data, 0)
        if hw[0] != 0xFFFF:
            slots.append(hw)
    print(f"{len(slots)} boot record(s), newest first\n")
    for n, hw in enumerate(slots):
        print(f"--- boot record -{n}")
        for i, v in enumerate(hw):
            note = ""
            if i == 1 and v != 0xFFFF:
                note = f"  [{csr_flags(v)}]"
            if i == 5 and v != 0xFFFF:
                note = f"  [PDWN={bool(v & 2)} FRES={bool(v & 1)} RESETM={bool(v & 0x400)}]"
            if i == 10 and v != 0xFFFF:
                note = f"  [RESET={bool(v & 0x400)} SUSP={bool(v & 0x800)} WKUP={bool(v & 0x1000)} CTR={bool(v & 0x8000)}]"
            print(f"  h{i:2d} {LABELS[i]:34s} {'--' if v == 0xFFFF else f'{v:#06x}'}{note}")


if __name__ == "__main__":
    main()
