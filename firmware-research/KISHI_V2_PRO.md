# Razer Kishi V2 Pro (RZ06-0458): first look

Goal: the same DualShock 4 approach as the V1, so the V2 Pro works on iPhone. Everything below comes from offline analysis of Razer's Android app
and Razer's public firmware; **nothing has been tested on a V2 Pro yet.** The APK and firmware images are Razer's copyright and stay out of the
repository (see `.gitignore`).

## Sources

- Razer Cortex Mobile 6.0.1 (`com.razer.bianca`, versionCode 6001003). It replaced Razer Nexus and still handles the Kishi controllers.
- Firmware manifest (public, no auth): `https://mobileapp-assets.razerzone.com/BiancaFw/T1/firmware_update_v3.json`
  → `BiancaFw/T1/02.02.00.00/firmwareHex.hex` (firmware 2.2). SHA-256 of the `.hex` as downloaded 2026-10-07:
  `E1D440A03CEF8668AA465F900A95BBA5666E32C5A9A47ACBEEEBD3B7487A3619`.
- Release notes: `BiancaFw/T1/firmware_release_note.json` (2.0 added vibration and Virtual Controller; 2.1 haptics with headphones;
  2.2 added XInput mode).

## Identity (from the app's product table, `com.razer.bianca.catlog.RazerProducts`)

Razer's internal names: "Bianca" is the app; the V2 Pro is **`KISHI_V2_TIER1`** (class `KishiV2T1Device`); the plain Kishi V2 is `KISHI_V2_TIER2`.

| Mode | VID:PID | Notes |
|---|---|---|
| V2 Pro, HID mode | `1532:0717` | `INPUT_MODE_HID` |
| V2 Pro, XInput+ mode | `1532:0718` | Xbox 360-style (see below) |
| Razer bootloader | `1532:110E` | product "Recovery" (`BOOTLOADER`) |
| Kishi V2 (not Pro) | `1532:0712`, `1532:071B` | for reference |
| Kishi V1 | `27F8:0BBF` | this repository's target |

The V2 Pro's XInput mode carries an MS OS descriptor (`MSFT100`) with compatible ID `XUSB10`, i.e. an Xbox 360 controller. As far as we know
iOS supports Xbox One/Series controllers but not the 360's XUSB protocol, so this mode is not a way onto iPhone.

## Hardware (from the 2.2 image)

- **MCU: NXP LPC55xx (Cortex-M33).** The image carries NXP MCUXpresso SDK 2.8.2 driver paths (`fsl_lpadc`, `fsl_flexcomm`, `fsl_gint`,
  `fsl_pint`, `fsl_i2s`, `fsl_i2s_dma`, `fsl_ctimer`, `fsl_wwdt`) and `components/audio/fsl_adapter_flexcomm_i2s.c`. Initial SP `0x04008000`
  is the top of the LPC55's 32 KB SRAMX. Exact part (LPC55S6x / LPC55S2x / ...) still unknown.
- **Flash map:** application at `0x00008000`, reset handler `0x00008215`, image ends at `0x0001EBFF` (93,184 bytes). Below `0x8000` is presumably
  Razer's bootloader (32 KB). Initialised data is stored compressed, so USB strings are not plain in the image (`Razer`, `Kishi V2` fragments).
- **Plaintext:** the `.hex` is neither encrypted nor wrapped, so pins and ADC channels can be recovered by disassembly, as for the V1
  (`BOARD_MAP.md`).
- **Audio:** I2S plus DMA drives the 3.5 mm jack. The app also updates multiple components (`FirmwareComponentId`, `[FW_MULTI_CHIP]` logs),
  so the board has more than one updatable chip.
- The app also bundles `res/raw/fw_1_00_01.hex` ("Razer Bianca Android"), a RAM-linked image (`0x20000000`) built on the same SDK. Its role is unknown.

## Update path (app → controller)

1. The app's Java side calls `BaseDeviceManager.updateDFUFirmwareVersion(hex)`, which hands the command `DfuFirmwareUpdater` and the image to
   Razer's native SDK (`libRazer_IoT_SDK.so`, Razer "V25" command set, HID feature reports with transaction ID and checksum).
2. If the device is not already in its bootloader, `UsbThread` first sends **`SetDeviceMode` with payload `[0x01, 0x00]`**. The controller
   re-enumerates as `1532:110E`. **No button combination is needed**: the switch is done in software.
3. The bootloader then receives, per the native SDK's step names: `GetDFUDeviceInformation` (10 bytes), `SetDFUChipSelection`,
   "Set Load Information", `EraseLoadRegion` / `DFUErase` (start/end address echoed back), `DFUProgram` (address and data echoed back),
   `DFUVerify` (readback compare), `CalcLoadCRC` (**CRC16** over the image length), then `DFUExit`.
4. App-side integrity is only a CRC over files inside the downloaded zip (`FirmwareIntegrityVerifierImpl`).

There are **no signature, key or encryption steps in the update path** we found (no such command names in the SDK's DFU code, and
the image is plaintext). That makes a custom image plausible, but the bootloader could still check something we cannot see, as the V1's
manifest check once did.

### Command bytes (from `libRazer_IoT_SDK.so`, arm64)

The Java side configures the "Razer Protocol2.5" interface (`ProtocolType.V25`) with a **90-byte feature report**, and the bootloader
(`BOOT_LOADER_COM_CONFIG`) uses the same configuration. The native packet code checks an XOR checksum over the packet, which matches OpenRazer's
`razer_report`: `[0]` status, `[1]` transaction ID, `[2:4]` remaining packets, `[4]` protocol type, `[5]` data size, `[6]` class, `[7]` ID,
`[8:88]` arguments, `[88]` XOR of bytes 2..87, `[89]` reserved. A read is a SET_FEATURE carrying the request and then a GET_FEATURE for the reply.
Bit 7 of the ID marks a read.

The SDK builds name ↔ (class, ID) maps in static initialisers. Each table's class is a global int: the DFU table's sits in `.data` (`0x10`);
the general table's is a `.bss` int that nothing writes, so it is `0x00`. Entries read off the disassembly one by one:

| Command | Class | ID | Request size (from the app) |
|---|---|---|---|
| `GetFirmwareVersion` | `0x00` | `0x81` | 4 |
| `SetSerialNumber` / `GetSerialNumber` | `0x00` | `0x02` / `0x82` | 16 |
| `GetProtocolVersion` | `0x00` | `0x83` | |
| `SetDeviceMode` / `GetDeviceMode` | `0x00` | `0x04` / `0x84` | 2 (`[0x01, 0x00]` = enter bootloader) |
| `GetEditionInformation` | `0x00` | `0x86` | 3 |
| `GetDFUDeviceInformation` | `0x10` | `0x80` | `0x50` as sent by the native updater (the Java side allocates 10) |

### Bootloader (DFU) commands, class `0x10`

Names and IDs paired from the SDK's ID→name map, where each entry stores the ID and assigns the name right after, so the pairing is exact.
Argument layouts come from each command's packet builder.

| Command | ID | Data size | Arguments |
|---|---|---|---|
| `SetDFUChipSelection` | `0x00` | | not used by the V25 updater |
| `DFUErase` | `0x01` | 8 | StartAddress u32 **big-endian**, EndAddress u32 big-endian; the reply must echo both |
| `DFUProgram` | `0x02` | 4 + n | StartAddress u32 BE, then n data bytes (longer writes use the "remaining packets" field); the reply must echo address and data |
| `DFUAbort` | `0x04` | 0 | sent on errors |
| `DFUExit` | `0x05` | **0** | none |
| `GetDFUDeviceInformation` | `0x80` | `0x50` | none; the reply goes back to the app as raw bytes (layout unknown) |
| `DFUVerify` | `0x83` | | 1 byte, StartAddress u32 BE, BinDataSize; the reply is the flash contents, compared with the image |

Order used by the V25 `DfuFirmwareUpdater`: `GetDFUDeviceInformation` → `DFUErase` → `DFUProgram`… → `DFUVerify`… → `DFUExit`
(`DFUAbort` on failure). It never uses `SetDFUChipSelection`, `GetBootloaderFirmwareVersion` or a signing or key step.

Implication for testing: the bootloader can be entered (`SetDeviceMode [0x01, 0x00]`), queried (`GetDFUDeviceInformation`) and left (`DFUExit`)
without ever sending `DFUErase`. Whether `DFUExit` boots the existing application after a session with no erase is **not proven**. It is the
natural reading, but the V1 taught us not to assume bootloader behaviour. The fallback is reflashing Razer's 2.2 image with the same protocol.

**On hardware (2026-10-07, Windows):** Razer's commands answer on interface 3 (usage `0x81/0x82`) with transaction ID `0x1F`; the
bootloader `1532:110E` ("RAZER BOOTLOADER") answers on interface 0. `SetDeviceMode [0x01, 0x00]` and `DFUExit` both work and get no
reply (the controller restarts first). `DFUExit` with no erase boots the existing application. Reflashing Razer's 2.2 erases,
programs and verifies 1456 blocks in 93 s. A one-byte modified image booted (as "Razer Xishi V2 Pro"): **the bootloader checks no
signature**. `GetDFUDeviceInformation` returns `07 26 00 00 01 00 00 07 17 00...` (meaning unknown).

**How the application enters the bootloader** (firmware 2.2, static): `SetDeviceMode` is case `0x02` (ID − 2) of the jump table at
`0x18432`; with payload byte 0 = 1 it calls `0x18754` → `0x140d2`, which disables interrupts, writes **`0xAAAAAAAA` to `0x20017FFC`**
(last word of SRAM1, kept across a reset) and requests a system reset (`0x12cb8`). A second routine (`0x174c0`) writes `0xD5D5D5D5` to
the same word before a reset; its meaning is unknown and we never write it. Our firmware enters the bootloader the same way
(`v2pro-firmware/bootloader.c`).

### Packet serializer (common to all commands)

Byte 1 is the transaction ID, byte 4 the protocol type, byte 5 the data size, byte 6 the class, byte 7 the ID, and bytes 8.. the data (80 bytes per
packet; bytes 2..3 count the remaining packets for longer payloads). Checksum: XOR. This is OpenRazer's `razer_report`.

The general-table IDs match OpenRazer's standard commands (0x81 firmware, 0x82 serial, 0x04/0x84 device mode), which supports the reading.
The transaction ID the V2 Pro expects is unknown (Razer devices use 0x1F, 0x3F or 0xFF).

`tools/v2pro_probe.py` sends only the read commands above. It refuses to build any packet whose ID has bit 7 clear.

### Tools

| File | Does |
|---|---|
| `tools/razer_v25.py` | Shared packet builder, reply parsing, interface and transaction-ID discovery |
| `tools/v2pro_probe.py` | Read-only queries (normal mode and bootloader), and `buttons`: the live factory read `0xFE/0xB0` (axes, triggers, pressed pins) |
| `tools/v2pro_dfu.py` | `enter` (SetDeviceMode `01 00` from Razer's firmware, KishiDS live command 7 from ours), `exit` (DFUExit), `plan` (offline) and `flash`: Razer's 2.2 by SHA-256, the signature-test image with `--signature-test`, and with `--custom` only a KishiDS V2 Pro build that passes its checks (base `0x8000`, inside Razer's span, sane vectors, stamped config block, recovery marker). Every write asks for "yes". It can't address below `0x8000`, sends DFUAbort on failure, and treats DFUVerify read-back, not the echoes, as the pass/fail check. |
| `tools/tests/test_v2pro_dfu.py` | Runs all of the above against a simulated controller and bootloader |

Assumptions in `v2pro_dfu.py` that only hardware can settle, all marked `ASSUMPTION` in the code:
- erase EndAddress is the image's last byte (inclusive vs exclusive is unknown; both give the same pages for the 2.2 image);
- replies echo the request layout, and DFUVerify's read-back follows the 5-byte header;
- the transaction ID is one of 0x1F / 0x3F / 0xFF. The tool finds it with read commands before writing anything.

## Porting impact

- Reusable: `report.c` (DS4 report), the config schema and live protocol, and most of the Windows app.
- Rewrite: clocks, GPIO, ADC, USB device stack and flash writes for LPC55 (NXP SDK, BSD-3-Clause; libopencm3 has no LPC55 support), and the
  flasher (Razer's protocol instead of DFU and `dfu-util`).
- A real wired DS4 is itself a composite device with USB audio, so keeping the headphone jack is possible in principle.

## Next steps

1. ~~Get the command bytes and write a read-only probe~~: done, `tools/v2pro_probe.py` (needs `hidapi`; not yet run on hardware).
2. ~~Disassemble the 2.2 image~~: pin map in [`BOARD_MAP_V2PRO.md`](BOARD_MAP_V2PRO.md) (buttons and analog pins resolved; button names
   and ADC channel ↔ axis to confirm with `v2pro_probe.py buttons`; outputs, I2S/codec and haptics still open).
3. With the controller, following [`V2PRO_HARDWARE_TEST.md`](V2PRO_HARDWARE_TEST.md): `v2pro_probe.py list` and `info` in HID and XInput modes. Then `v2pro_dfu.py enter`, `v2pro_probe.py dfuinfo`
   and `v2pro_dfu.py exit`. **Download Razer's 2.2 `.hex` first** (URL above) and run `v2pro_dfu.py plan <hex>`, so `flash` is ready if
   the controller stays in the bootloader.
4. Firmware in [`v2pro-firmware/`](v2pro-firmware/README.md): start-up, clocks (48 MHz, voltage untouched), buttons,
   sticks and triggers (LPADC, set up as Razer's), DS4 reports and USB (TinyUSB, enumeration identical to the V1) are written
   and tested in emulation and on the host, and so are saved settings (power-cut safe). Nothing has run on a controller yet.
5. Signature test ready: `v2pro_dfu.py sigtest` builds Razer's 2.2 with the USB product string's "K" changed to "X"
   (one literal byte of the compressed data, checked by emulating both start-ups), and `flash --signature-test` writes it.
   Phase 4 of `V2PRO_HARDWARE_TEST.md`.
6. Prove that Razer's own 2.2 image can be reflashed (the recovery route) before trying any modified image. Then try a one-byte mutation
   (as with the V1's `Kishx` test) to find out whether the bootloader checks anything beyond CRC16.
