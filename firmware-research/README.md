# Razer Kishi V1 firmware research

> **Not included in this repository:** Razer's APK (`razer-kishi-official.apk`), the firmware images extracted from it (`official-images/`), their decompiled sources and any device backups (`device-backups/`) are Razer's copyright and are git-ignored. The notes below describe them so you can reproduce the analysis with your own copy.

## Device observed

- Normal mode: `27F8:0BBF` — Kishi USB HID game controller.
- Firmware-update mode: `27F8:0BC0` — `Bootloader_GV`, USB DFU class `FE/01/02`.
- DFU identity: `GV Bootloader V1.0.2`, serial `12345678`, alternate setting `0`.

## Preserved vendor material

The official Razer Kishi APK is saved as `razer-kishi-official.apk`.
Its SHA-256 is:

`93AB4686C55508BC7D4A4EE9BAB7AB63F36B25A4C87F12C690EE3814F8B17741`

Firmware images were extracted from the APK into `official-images/`.
The likely original-Kishi image is `assets_legacy_02.70.bin`:

- Size: 28,108 bytes
- SHA-256: `AA915363C858E5A06FB1AD15A82D059D50FD22A625D0663A3CE99FCAB24F69BE`
- ARM Cortex-M vector table: initial stack pointer `0x20004000`; reset handler `0x08009841`.

## Read-back result and write safety rule

`dfu-util` can enumerate the DFU bootloader but its DFU upload/read-back request is rejected immediately with `LIBUSB_ERROR_PIPE`. Zero firmware bytes were read.

The official v2.70 image has now been used successfully to restore this physical controller after every controlled test.  Two full replacement variants were rejected during manifest with DFU status 7 (“Programmed memory failed verification”), even after one was padded to the exact 28,108-byte stock length.  Neither replacement booted.

Subsequent one-byte, stock-derived experiments proved that the bootloader does **not** require the stock file's exact whole-image hash or a whole-image signature: a product-string change booted and enumerated as `Kishx`.  The actual replacement blocker is therefore an unresolved layout, protected-region, or runtime requirement.  See `BRINGUP_HISTORY.md` for the exact experiments and current safe boundary.

Windows recorded this controller as `USB\\VID_27F8&PID_0BBF&REV_0270`, proving it is running firmware 2.70. The official `02.70` image therefore matches the controller's installed revision. A dedicated copy is stored as `device-backups/kishi-stock-v2.70-recovery.bin` with the same SHA-256 as above.

The stock image has been used twice to restore the controller after rejected test images.  That is a sound recovery route for this exact device and stock file, but it does not make arbitrary images safe.

## Static analysis of the official updater

The APK was decompiled locally into `apk-decompiled/` for read-only analysis. Its updater uses standard USB DFU control requests against `27F8:0BC0`:

- `GET_STATUS` (`0xA1/0x03`), followed by `CLEAR_STATUS` (`0x21/0x04`)
- `DNLOAD` (`0x21/0x01`) in raw 1,024-byte blocks on alternate setting `0`
- A final zero-length `DNLOAD` request to end the transfer

No app-side signature verification, encryption, or image container was found. That does **not** prove that the bootloader does not check an image checksum or other condition after download.

The updater hard-codes the legacy firmware version as `2.70` and selects the legacy image for controllers whose major firmware version is not above 10. The archived `assets_legacy_02.70.bin` is therefore the official update/recovery image for the original Kishi MCU family.

## Static descriptor locations in the 2.70 image

The legacy image contains the exact runtime USB data observed from the controller:

- HID report descriptor: file offset `0x6CA8` (199 bytes)
- USB device descriptor: file offset `0x6D70`
  - VID:PID `27F8:0BBF`
  - device revision `0x0270` (firmware 2.70)
  - USB HID device class `0x03`
- USB strings include `Kishi`, `Razer`, `Gamevice HID Config`, and `Gamevice HID Interface`.

The report descriptor embedded in the firmware byte-matches the descriptor captured from the physical controller. This confirms that the firmware file and the attached Kishi are an exact revision match.
