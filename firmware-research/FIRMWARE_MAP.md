# Kishi 0290 v2.70: firmware map and modification boundary

> **2026-10-06 CORRECTION:** the real application base is **`0x08003000`** (app spans `0x08003000`-`0x08009DCC`; reset handler at image offset `0x6840`), not `0x08009000` as stated below.  See `BRINGUP_HISTORY.md`.  All "file offset" values in this file are still correct; only the absolute-address and "64 KiB layout / `0x0840` reset" statements are wrong.

## Verified recovery material

The connected controller reports `REV_0270`. Its exact official image is:

`device-backups/kishi-stock-v2.70-recovery.bin`

SHA-256:

`AA915363C858E5A06FB1AD15A82D059D50FD22A625D0663A3CE99FCAB24F69BE`

## Application image placement

The image is self-consistently linked for the application slot beginning at flash address `0x08009000`:

- its vector table is at image offset `0x0000`;
- its reset-vector value is `0x08009841`, which resolves to executable code at image offset `0x0840` when that base is used;
- its interrupt-vector values also resolve to code within the image at that base.

The 28,108-byte image therefore spans `0x08009000` through `0x0800FDCC` (exclusive end).  This strongly supports a 64 KiB application-plus-bootloader flash layout, with the bootloader occupying the preceding region.  The exact MCU model and the bootloader's enforcement rules are still unconfirmed, so a replacement must remain within this same application-slot boundary and must not touch the vendor bootloader.

## USB data embedded in the firmware image

| Data | File offset | Details |
|---|---:|---|
| HID report descriptor | `0x6CA8` | 199-byte descriptor; exact match for the physical Kishi |
| USB device descriptor | `0x6D70` | USB 2.00, HID class, `27F8:0BBF`, revision `0x0270` |
| USB strings | `0x6AA0` | `Kishi`, `Razer`, `Gamevice HID Config`, `Gamevice HID Interface` |

## USB implementation anchor (static analysis)

At file offset `0x6614`, the application initializes a USB context at RAM address `0x20000BEC` and stores the STM32 USB peripheral base `0x40005C00` into it. It then configures endpoint addresses `0x00`, `0x80`, `0x81`, and `0x01`, matching endpoint zero plus the normal HID control, interrupt-IN, and interrupt-OUT paths.

This gives a concrete starting point for tracing the existing USB implementation and confirms the controller's report transport is firmware-controlled, rather than a separate fixed USB/HID chip.

Further static tracing identified the normal HID path:

| File offset | Role | Evidence |
|---:|---|---|
| `0x3FBC` | Configures the normal interrupt endpoints | Opens `0x81` and `0x01` as interrupt endpoints with the current 12-byte packet size |
| `0x4000` | Queues an input transfer on endpoint `0x81` | Uses the USB transport context and endpoint address `0x81` |
| `0x6450` | Controller-specific wrapper around the endpoint-`0x81` sender | Provides the normal HID transport context |
| `0x582E` | Sends the completed Kishi state | Calls `0x6450` with a report length of `11` bytes after the input report is assembled |

This is the most promising modification boundary: retain the original board-scanning code, insert a report translator after the 11-byte state is built, and replace the normal HID descriptor/endpoint configuration with a target-controller implementation. It is still necessary to resolve code space, target descriptors, feature responses, and the bootloader's application expectations before building or flashing such a patch.

## Input-scanner anchor (static analysis)

The routine at file offset `0x544C` reads fifteen digital controls from the STM32F0 GPIO ports into RAM at `0x20000404`.  It uses GPIOA, GPIOB, and GPIOC; the observed input masks are:

| GPIO port | Input masks read |
|---|---|
| A | `0x0001`, `0x0010`, `0x0080`, `0x0100` |
| B | `0x0002`, `0x0004`, `0x0400`, `0x0800` |
| C | `0x0001`, `0x0002`, `0x0004`, `0x0008`, `0x0010`, `0x0020`, `0x0100`, `0x2000` |

The application loop at `0x5700` combines this digital state with six analogue readings, then builds the normal 11-byte Kishi report at `0x5774`–`0x582A`.  It sends that finished report at `0x582E`.

This confirms that a future replacement application does not have to rediscover the hardware electrically: the original firmware already contains a complete input path for every control.  The safest design direction remains to preserve that path and translate its finished state immediately before USB transmission.

## First replacement-image build and bootloader validation

`ds4-firmware/kishi_ds4_stage1.bin` was compiled on 2026-10-05 as a USB-only enumerator for the inferred STM32F072x8 target.  It has these verified properties:

| Check | Result |
|---|---|
| Link address | `0x08009000` |
| Raw image size | 7,416 bytes (`0x1CF8`) |
| Application-slot limit | 28 KiB (`0x7000`) |
| Initial stack pointer | `0x20004000` |
| USB HID descriptor | 483-byte captured wired-DS4 descriptor |
| Input/output endpoints | interrupt IN 64 bytes (`0x81`); interrupt OUT 32 bytes (`0x01`) |

This stage deliberately sends neutral input only and accepts/ignores host output.  It is an enumeration and recovery test, **not yet a usable controller build**.

On 2026-10-05, two deliberately limited DFU validation attempts were made against the physical controller:

| Image | Length | Result |
|---|---:|---|
| `kishi_ds4_stage1.bin` | 7,416 bytes | All blocks accepted, then bootloader returned `dfuERROR`, status 7: `Programmed memory failed verification` |
| `kishi_ds4_stage1_slot.bin` | 28,108 bytes | Same final verification failure; padding it to the exact stock-image length did not change the result |

The official v2.70 recovery image was immediately retransferred after *each* rejection.  On both occasions the Kishi rebooted and Windows enumerated its normal `27F8:0BBF` HID game-controller device successfully.

This rules out image length as the explanation.  Later stock-derived one-byte experiments prove the bootloader does **not** require an exact whole-image hash/signature: a string-data mutation booted and enumerated successfully.  The replacement blocker is instead an unresolved format, protected-region, programming-layout, or runtime requirement.  The exact experiment record is in `BRINGUP_HISTORY.md`.

## Offline integrity checks

`tools/analyze_image_integrity.py` reads the saved stock image only; it never accesses USB.  On v2.70 it found no stored field matching either of the common whole-image or aligned-prefix layouts for:

- standard CRC-32/ISO-HDLC; or
- the STM32 CRC-32/MPEG-2 peripheral convention.

The final four bytes are `F9 97 00 08`, an in-image Thumb-address pointer rather than either candidate CRC.  The file also starts directly with the application vector table rather than a recognizable signed-image container.  The successful one-byte string mutation now rules out an all-byte bootloader signature/checksum, but it does not rule out region-specific metadata or an application-side self-check.

## Official firmware-transfer behavior

The Razer Android APK sends raw `legacy/02.70.bin` over standard DFU:

1. `GET_STATUS`
2. `CLEAR_STATUS`
3. `DNLOAD` blocks of 1,024 bytes on alternate setting 0
4. A final zero-length `DNLOAD`

The app does not encrypt, sign, or wrap the image before it transfers it.  Full replacements fail at final manifest while controlled stock-derived mutations can boot, so the meaningful unknown is not an all-byte cryptographic gate but the required stock-image structure or runtime behavior.

## Recovery test (completed)

On 2026-10-05, the exact image above was transferred to the physical controller's `27F8:0BC0` DFU bootloader using 1,024-byte standard DFU download blocks. The entire 28,108-byte transfer completed, the device restarted, and Windows re-enumerated it successfully as the normal `27F8:0BBF` Kishi with a healthy HID game-controller child device.

This proves that the official v2.70 image is a working restoration path for this controller. It does **not** prove that an arbitrary custom image will boot or that every failed experimental image will leave DFU reachable.

## What this means for the iPhone project

Changing the Kishi's USB name, VID/PID, or HID descriptor alone would not create an Xbox controller. Wired Xbox support uses a separate protocol family (GIP/XUSB/XInputHID) with its own controller discovery, state-report, and host-command behavior.

A genuine internal conversion therefore requires either:

1. Substantial binary reverse engineering and a replacement USB protocol implementation for the existing MCU, or
2. Replacing/interposing the controller electronics with a microcontroller that translates Kishi inputs into a supported target protocol.

An experimental full replacement was transferred twice for validation but failed at final manifest.  Later stock-derived mutations demonstrated that descriptor/data bytes can be modified and boot.  The controller is currently restored to stock v2.70.
