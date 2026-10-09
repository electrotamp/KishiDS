# Kishi V2 Pro (RZ06-0458) board map (recovered from Razer firmware 2.2; buttons and sticks checked on hardware)

Addresses are into Razer's `BiancaFw/T1/02.02.00.00/firmwareHex.hex` (application at `0x8000`; see `KISHI_V2_PRO.md`). Method: Capstone
disassembly plus Unicorn emulation of the real image (scanner, report builders, and the startup code that decompresses `.data`).
Confidence is marked per row: **static** = read directly from code, **emulated** = observed by running the firmware's own code,
**hypothesis** = inferred, to confirm with `tools/v2pro_probe.py buttons`.

## MCU, clocks, USB
- NXP LPC55xx (Cortex-M33) with **640 KB flash**. The firmware reads the protected flash region at `0x9FCD8` (NMPA), which only exists on
  the 640 KB parts (LPC55S6x / LPC55S2x / LPC552x).
- USB: **USB0, full speed** (`0x40084000`). USB1 high speed is never touched, so the wired DS4 target (also full speed) fits.
- Device descriptors in RAM after start-up: `1532:0717` (HID mode, "Razer Kishi V2 Pro"), `1532:0718`, and `1532:0037` (the app's XInput
  entry, shown as "55" there), with the MS OS string `MSFT100`.

## Buttons (static: scanner at `0x192ea`, IOCON in `BOARD_InitPins` at `0x153ce`)
All button pins are GPIO inputs with the internal pull-up (IOCON `0x120` = digital, pull-up), so presumably active-low like the V1.
The scanner reads 18 pins into a 20-slot word. Slots 1 and 11 have no pin (forced to 1); they line up with the analog triggers.
Each slot is debounced over 28 samples, then sets bit *i* of the press mask at `0x20000CA4`.

| Scanner index | Pin | Diag bit | Name (**hardware** = confirmed on a real controller, 2026-10-07) |
|---|---|---|---|
| 4 | P1_7 | 0 | D-pad Up (hardware) |
| 7 | P0_22 | 1 | D-pad Right (hardware) |
| 5 | P0_24 | 2 | D-pad Down (hardware) |
| 6 | P1_17 | 3 | D-pad Left (hardware) |
| 15 | P0_4 | 4 | A (hardware) |
| 16 | P0_6 | 5 | B (hardware) |
| 17 | P0_8 | 6 | X (iPhone, 2026-10-09) |
| 14 | P0_9 | 7 | Y (iPhone, 2026-10-09) |
| 2 | P0_0 | 8 | L1 (hardware) |
| 12 | P0_17 | 9 | R1 (hardware) |
| 1 | — | (10) | L2 (analog) |
| 11 | — | (11) | R2 (analog) |
| 3 | P1_14 | 12 | L3 (hardware) |
| 13 | P0_18 | 13 | R3 (hardware) |
| 0 | P0_27 | 14 | M1, left programmable (hardware) |
| 10 | P0_31 | 15 | M2, right programmable (hardware) |
| 8 | P1_26 | 16 | **View / Back** (hardware; static: table `0x1D61C` maps it to XInput Back) |
| 18 | P0_19 | 17 | **Menu / Start** (hardware; static: table `0x1D61C` maps it to XInput Start) |
| 19 | P1_6 | 18 | Nexus / Home, game controller icon (iPhone, 2026-10-09) |
| 9 | P1_5 | 19 | Share / screenshot (iPhone, 2026-10-09; table `0x1D61C` maps it to output bit 20, HID button 17) |

"Diag bit" is the bit in Razer's factory read `0xFE/0xB0` (reply data bytes 6–8). The pairing of scanner index, pin and diag bit is
**emulated** and certain. The names were first guessed from the diag order, read as a factory checklist; on hardware the first eight
turned out to be the D-pad first and then the face buttons, not the other way round. The captures (`v2pro_probe.py buttons`, Windows,
interface 3, default transaction ID) are in `device-backups/buttons_win*.txt`. In the first capture the last four presses came out
of order; the recheck (`buttons_win_recheck.txt`: A, Up, Menu, Share) is the reference for Menu and Share, and Nexus is the one pin left. On an iPhone (2026-10-09) X/Y and Share/Nexus turned out the other way round (what iOS did
with each button: Nexus did nothing, the screenshot button acted as PS), so the table above follows the iPhone.

`P1_23` (pull-up input) is read separately (`0xc7f2`, `0xd790`) and is not a gamepad button; it may be a detect line (headphones, phone clamp).

## Analog (static + emulated)
Six analog pins (IOCON `0x400` = analog switch on, digital off), on LPADC0 (`0x400A0000`). Channels from the LPC55 pin functions,
confirmed by Razer's command table at `0x1bac4` and by the register trace of its start-up:

| Pin | ADC channel | Razer command | Role in Razer's code |
|---|---|---|---|
| P1_8 | ADC0_4 (4A) | 1 | stick "1", first axis |
| P1_0 | ADC0_11 (3B) | 2 | stick "1", second axis |
| P0_10 | ADC0_1 (1A) | 3 | trigger, HID brake axis (L2, hardware) |
| P0_16 | ADC0_8 (0B) | 4 | stick "2", first axis |
| P0_23 | ADC0_0 (0A) | 5 | stick "2", second axis |
| P0_15 | ADC0_2 (2A) | 6 | trigger, HID accelerator axis (R2, hardware) |

Razer's analog task (`0x16a14`) reads each channel through its own software trigger into 10-sample buffers. `0xc860` builds the sticks
from the pairs above, and `0xed42` builds the triggers. The report writer (`0x9d7e`) puts stick "1" on X/Y and stick "2" on Z/Rz, swapped
when a profile setting says so. **Triggers read lower when pressed** (`0xed42` returns 0 above its rest value and full scale below its
pressed value), as on the V1. **On hardware** (diag read, default profile): the left stick is stick "1" (report X/Y), the right
stick is stick "2" (Z/Rz), both report 255 = right and 0 = up, and L2 is the brake axis (P0_10), R2 the accelerator (P0_15). Still
open: inside each stick, which pin is X and which is Y, and the raw ADC polarity (needs the raw values, our firmware's report `0xAB`).
Set-up as Razer: ADC clock = main clock / 8 = 12 MHz, `CFG = 0x10800040`, calibration with 128 averages, 12-bit single-ended conversions.

## Report layouts (emulated)
- **HID mode** report (ID 1, at `0x20002BC8`): X, Y, Z, Rz (8 bits each), hat (4 bits), 18 buttons (usage 1–18), 10 padding bits,
  brake and accelerator (8 bits each), and two vendor bytes (`0xFF00/0x21`).
- **Internal button word** `W` (`0x20000CBE`), which feeds both reports, in Android HID order: bits 0–3 D-pad up/down/left/right, 4 A, 5 B,
  6 C, 7 X, 8 Y, 9 Z, 10 LB, 11 RB, 12 LT (digital), 13 RT (digital), 14 Back, 15 Start, 16 Guide, 17 L3, 18 R3, 19–21 extras.
- **XInput** report (at `0x20002BC8 + 0x29`, built at `0x19ff2`): standard 20-byte layout from `W`.
- Physical index → `W` goes through Razer's runtime remapping (button-mapping profile), which is applied at run time. Only View, Menu and
  Share are in a fixed table (`0x1D61C`).

## Outputs and buses (static, purpose unknown)
- Driven high at start-up (GPIO output, level 1): **P1_18, P1_12, P1_9, P0_2, P0_3, P1_25**. Some have a peripheral function set in IOCON
  too: P0_2 (func 3), P0_3 (func 3, pull-up), P1_25 (func 2), P1_12 (digital). **On hardware:** without P1_18/P1_12/P1_9 high the
  sticks and triggers read near 0, so one of them supplies the potentiometers (which one is not isolated yet); our firmware drives
  all three.
- **RGB LED (hardware, 2026-10-08):** P0_2 = red, P0_3 = green, P1_25 = blue, on SCTimer outputs SCT0_OUT0/1/2 (IOCON functions 3, 3,
  2), lit while the output is high. Razer: set-up at `0xe77a` (one 16-bit counter limited at 0xFFFF, event 0 sets the outputs, events
  1-3 clear them), colour at `0xc9e6` (MATCHREL1..3 = channel x brightness), a colour it uses is (255, 144, 19) at brightness 76.
  Writing the MATCH registers while either counter half runs is a precise bus error (keep HALT_H set).
- P1_20 / P1_21: function 5, likely a FLEXCOMM I2C pair (audio codec or haptics driver; the firmware links `fsl_i2c` and `fsl_i2s`).
- P1_28 / P1_29 / P1_30 / P1_31: function 1 with pull-up, a FLEXCOMM serial group. P0_7 (func 1, open-drain) and P0_1 (func 2, open-drain) are
  another bus. To be matched against the LPC55 pin-function table.
- Second pin set-up routines (`0x198c0` and `0x19a4x`–`0x19c30`) put almost every pin back to plain input (low-power or suspend path).

## How to finish the map with the controller (read-only)
1. `python tools/v2pro_probe.py buttons 60` and press each button once: it prints `bitN=idxM/Pin`. Fill in the Name column.
2. Move each stick axis and trigger alone to see which of the six analog values changes.
