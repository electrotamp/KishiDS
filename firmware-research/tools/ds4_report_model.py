"""Pure Kishi-to-DualShock-4 USB input-report model.

This is an offline test model only. It does not open USB devices or write
firmware. The 64-byte DS4 USB layout follows the mainline Linux kernel's
drivers/hid/hid-playstation.c dualshock4_input_report_usb structure.
"""

DS4_INPUT_SIZE = 64
KISHI_INPUT_SIZE = 11


def _axis_to_unsigned(raw: int) -> int:
    """Convert Kishi signed 8-bit axes (centre 0) to DS4 unsigned axes (centre 128)."""
    signed = raw if raw < 0x80 else raw - 0x100
    return max(0, min(0xFF, signed + 0x80))


def encode_ds4_usb_input(kishi: bytes) -> bytes:
    """Translate one verified Kishi v2.70 input report to a DS4-style USB report."""
    if len(kishi) != KISHI_INPUT_SIZE:
        raise ValueError(f"Expected {KISHI_INPUT_SIZE} Kishi bytes, received {len(kishi)}")

    report = bytearray(DS4_INPUT_SIZE)
    report[0] = 0x01  # DS4 USB input-report ID

    # DS4 common report: left/right sticks at bytes 1..4.
    report[1] = _axis_to_unsigned(kishi[0])
    report[2] = _axis_to_unsigned(kishi[1])
    report[3] = _axis_to_unsigned(kishi[2])
    report[4] = _axis_to_unsigned(kishi[3])

    # Kishi's D-pad lives in the high nibble (0=up, 2=right, 4=down,
    # 6=left, 8=neutral). DS4 uses those same 0..8 hat positions in bits 0..3.
    report[5] = (kishi[4] >> 4) & 0x0F

    primary = kishi[6]
    secondary = kishi[7]

    # DS4 buttons[0]: Square, Cross, Circle, Triangle in bits 4..7.
    if primary & 0x08:  # Kishi X
        report[5] |= 0x10
    if primary & 0x01:  # Kishi A
        report[5] |= 0x20
    if primary & 0x02:  # Kishi B
        report[5] |= 0x40
    if primary & 0x10:  # Kishi Y
        report[5] |= 0x80

    # DS4 buttons[1]: L1/R1/L2/R2, Share, Options, L3, R3.
    if primary & 0x40:
        report[6] |= 0x01
    if primary & 0x80:
        report[6] |= 0x02
    if secondary & 0x01:
        report[6] |= 0x04
    if secondary & 0x02:
        report[6] |= 0x08
    if secondary & 0x04:  # Left Function -> Share
        report[6] |= 0x10
    if secondary & 0x08:  # Right Function -> Options
        report[6] |= 0x20
    if secondary & 0x20:
        report[6] |= 0x40
    if secondary & 0x40:
        report[6] |= 0x80

    # DS4 buttons[2]: PS/Home.
    if secondary & 0x10:
        report[7] |= 0x01

    # DS4 z/rz are L2/R2 analogue values. Kishi exposes R2 at byte 10 and L2 at 11.
    report[8] = kishi[10]
    report[9] = kishi[9]

    # Wired + full battery status. Touch report count remains zero because the Kishi has no touchpad.
    report[30] = 0x1B
    return bytes(report)


if __name__ == "__main__":
    neutral_kishi = bytes((0x00, 0x00, 0x00, 0x00, 0x80, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00))
    neutral = encode_ds4_usb_input(neutral_kishi)
    assert len(neutral) == 64
    assert neutral[0] == 0x01
    assert neutral[1:5] == bytes((0x80, 0x80, 0x80, 0x80))
    assert neutral[5] == 0x08
    assert neutral[30] == 0x1B

    # A / B / X / Y, Home, Functions, stick-clicks, shoulder buttons,
    # and both analogue triggers exercise every confirmed Kishi control field.
    exercised = bytearray(neutral_kishi)
    exercised[6] = 0xDB  # A, B, X, Y, L1, R1
    exercised[7] = 0x7F  # L2/R2 digital, Functions, Home, L3, R3
    exercised[9] = 0xA5  # R2 analogue
    exercised[10] = 0x5A  # L2 analogue
    mapped = encode_ds4_usb_input(bytes(exercised))
    assert mapped[5] == 0xF8
    assert mapped[6] == 0xFF
    assert mapped[7] == 0x01
    assert mapped[8:10] == bytes((0x5A, 0xA5))

    print("mapping self-test passed")
