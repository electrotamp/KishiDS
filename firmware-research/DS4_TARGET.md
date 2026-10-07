# DualShock 4-style wired target: concrete requirements

This is a research target for a replacement Kishi application. It is not an assertion that iOS will accept an emulator; final acceptance can only be tested on an iPhone.

## Why this target is technically within the Kishi's USB envelope

Mainline Linux's PlayStation HID driver identifies the following wired DualShock 4 report sizes:

| Direction/type | Report ID | Size |
|---|---:|---:|
| Main input | `0x01` | 64 bytes |
| Main output | `0x05` | 32 bytes |
| Calibration feature | `0x02` | 37 bytes |
| Firmware-info feature | `0xA3` | 49 bytes |
| Pairing-info feature | `0x12` | 16 bytes |

The Kishi's confirmed STM32F0 USB device controller supports full-speed USB and endpoint packets up to 64 bytes. The Kishi already has interrupt-IN and interrupt-OUT paths, so the report sizes themselves are not a hardware blocker.

## Required application behavior

1. Present a complete target device/configuration/HID/report descriptor set.
2. Send 64-byte report-`0x01` input states at the target polling cadence.
3. Translate the Kishi's four axes, analogue triggers, D-pad, face/shoulder buttons, stick clicks, View/Start, and Function buttons into the target layout.
4. Return valid, internally consistent values for feature reports. For Kishi-missing hardware (motion, touchpad, battery, light bar), report neutral/inactive values where the protocol permits.
5. Receive and safely ignore or partially implement report-`0x05` output commands until a need for haptics/LED behavior is established.
6. Retain a reliable entry route to the separate vendor DFU bootloader.

## Reference profile selected

Android's Compatibility Test Suite includes a captured registration profile for a genuine wired DualShock 4 CUH-ZCT1U (`054C:05C4`).  It provides the complete 483-byte HID report descriptor and sample responses for feature reports `0x02` (calibration), `0xA3` (firmware info), and `0x81` (Bluetooth address).  This gives the project an exact descriptor/feature-response reference rather than a guessed generic gamepad descriptor.

For the Kishi project, this profile is a **wire-format reference**, not a claim that the Kishi is a Sony controller or that a copied identity alone will be accepted by iOS.  The implementation must use its own clearly identified firmware build and must still answer the target controller's observable HID requests consistently.

## Proposed physical-control translation

| Kishi control | DualShock-style output |
|---|---|
| A (bottom) | Cross |
| B (right) | Circle |
| X (left) | Square |
| Y (top) | Triangle |
| L1 / R1 / L2 / R2 | L1 / R1 / L2 / R2 |
| Left / right sticks and L3 / R3 | Left / right sticks and L3 / R3 |
| D-pad | D-pad hat switch |
| Left Function | Share |
| Right Function | Options |
| Home | PS/Home |

The Kishi has no touch surface, motion sensors, battery, speaker, light bar, or rumble motors. A first version would return inactive/neutral values for the corresponding target fields and safely consume host output commands.

## Still required before a device build

- iPhone post-enumeration request sequence. The Android CTS DS4 profile resolves the descriptor and several feature payloads; an iPhone-specific trace or real-device trial is still the test of acceptance.
- Completion of the Kishi firmware's GPIO/ADC input-scanning map.
- Resolution of the vendor bootloader's application-address/entry expectations.
- A small replacement application that can be flashed and recovered using the now-proven v2.70 restore path.

## Primary implementation reference

The report IDs and sizes above are taken from the active Linux kernel PlayStation HID driver (`drivers/hid/hid-playstation.c`), which supports DualShock 4 and DualSense devices.
