# KishiDS engineering notes

How the pieces fit together, what was learned the hard way, and what is still unknown. For build steps see [`BUILDING.md`](BUILDING.md);
for the raw reverse-engineering log see [`../firmware-research/BRINGUP_HISTORY.md`](../firmware-research/BRINGUP_HISTORY.md) (older, partly superseded).

## What this is

Custom firmware for the Razer Kishi V1 (RZ06-0290, STM32F072, 64 KB flash) that presents as a wired DualShock 4 (`054C:05C4`), and a Windows app
(KishiDS) that edits its settings live over USB and flashes it. The firmware is flashed through the controller's own vendor bootloader
(`GV Bootloader`, USB DFU, `27F8:0BC0`), which is entered by holding **Y + B + Right Function** while plugging in. That bootloader is never
modified, so DFU recovery always works.

### Verified on hardware (one unit)

All face/shoulder/stick/trigger/D-pad/function controls and the DS4 report; cold plug (no buttons) and warm flash; iPhone recognising the
controller; live editing (push, read-back, save to flash, reboot, persistence); the serial number, manufacturer and product strings as seen by
Windows; the app reading the live controller. The app's pages were reviewed from rendered screenshots as well as on screen.

## Flash map (64 KB)

| Range | Contents |
|---|---|
| `0x08000000-0x08002FFF` | Razer's `GV Bootloader V1.0.2` (never touched) |
| `0x08003000-0x0800DFFF` | Application (ours: link base **0x08003000**; stock v2.70 also lives here) |
| `0x0800E000` | DIAG builds only: boot log page |
| `0x0800F000` | Saved settings record (264 bytes, commit word last) |
| `0x0800F800` | Factory page, **read only for us**: stock calibration (0x59 bytes) with the per-unit **serial number as ASCII at `0x0800F840`** |

The bootloader only checks that the image's vector table lies inside the app region; a wrong link base shows up as DFU status 7
("verification failed"). RAM is used from `0x20000100`.

## Firmware (`firmware-research/ds4-firmware`)

C99 on libopencm3, built with devkitARM. Hard-won facts:

- Clocks: HSI48 -> PLL (/2 x2) = 48 MHz, USB from HSI48 with CRS auto-trim. The stock firmware never enables the HSE crystal.
- **Never write USB endpoint `0x81` before `SET_CONFIGURATION`.** Packet memory is plain SRAM with random contents after power-on, so an early
  write goes through a garbage pointer and hangs on a cold plug. It "works" right after a DFU flash, which is why this hid for so long. The firmware
  gates endpoint writes on a `configured` flag. **After any firmware change, test a cold unplug/replug, not just a post-flash check.**
- Blue LED = PB4, TIM3_CH1, active-low, gamma-corrected PWM (`led.c`). Pin, ADC and calibration maps: [`BOARD_MAP.md`](../firmware-research/BOARD_MAP.md).
- PB14 and PC9 are driven high at boot because stock does; their purpose is unknown.

### Config block (the contract between firmware and app)

256 bytes, located in the image by the 12-byte marker `"KISHICFG"` + `0x0001` + `0x0100`. The schema lives **only** in
`firmware-research/tools/gen_config.py`, which generates `ds4-firmware/config_layout.h` and `KishiDS/Core/ConfigLayout.g.cs`. The block is
`const volatile` and read byte-wise so the compiler can't fold the defaults into code (a patched image would otherwise be ignored). At boot the
firmware validates magic, version, size and CRC-32 (bytes 16..255) and clamps every field; anything wrong falls back to built-in defaults.

Locked fields (`locked=True` in the schema; forced by the firmware's clamp and ignored by the app): USB VID/PID/bcdDevice (DS4), manufacturer
(`ElectroTamp KishiDS`) and serial. Editable identity: product string ("Device name") and poll interval.

### Serial number

The USB serial string is the controller's own factory serial (the number on its sticker). `kcfg_resolve_serial()` reads the 16-byte field at
`0x0800F840` and uses it if it holds 1-15 printable characters plus a NUL; otherwise it falls back to the 96-bit chip ID (`0x1FFFF7AC`) as 24 hex
digits. The DFU bootloader's own serial is a different, chip-ID-derived number and is not shown by the app.

### Live editing over USB

Vendor commands on HID feature report `0xAC` (signature `0x4B` + command: stage chunk / apply / read-select / save / erase / reboot); the spec is in
`live.h`. A config applies instantly. `SAVE` writes the saved record to `0x0800F000`; at boot it is used only if its `image_id` (CRC of the image's
config block) matches, so any DFU flash with a different config supersedes it. Identity changes (name, poll rate) need a reboot because the strings
are copied at boot; telemetry flags this, and `REBOOT` resets over USB without DFU.

### Telemetry (feature report `0xAB`)

`[0xAB][ver=1][flags][buttons u16][ADC u16 x6][active config CRC u32 @17][persisted CRC u32 @21][protocol @25]`. Flags: b0 configured, b1 config
from image, b2 live-capable, b3 from saved record, b4 unsaved changes, b5 identity differs. ADC order: RX, RY, R2, LY, LX, L2. The app uses it for the
live controller view (physical buttons, before remapping), calibration, and to verify a flash by comparing config CRCs.

### DIAG builds

`DIAG=1 ./build.sh` builds `kishi_ds4_diag.bin`: a flash boot log at `0x0800E000` (read with `tools/diag_log.py`), a register snapshot, raw ADC in report
bytes 13-26, an LED heartbeat, and a read-only memory dump (SET_FEATURE `0xF2` selects an address, GET_FEATURE `0xB0` returns 63 bytes; allow-listed ranges
only; `tools/diag_mem.py`). The release image contains none of this (`arm-none-eabi-nm kishi_ds4.elf | grep -i diag` is empty).

### Tests

- `tests/run_tests.sh`: host C unit tests (report building, config, live protocol, serial resolution).
- `tests/emu_test.py`: runs the real ARM image in the Unicorn emulator and compares `report_build` with the host build on 400 random cases, and checks
  config loading, the live protocol, saved-record boot and serial resolution on the ARM code.

## KishiDS app (`KishiDS/`)

.NET 9 WPF with no NuGet packages. `Theme/` has the flat dark theme, `Controls/` the custom-drawn visuals, `Pages/` one view per page, `Core/` the logic:
`AppModel` (shared state, apply/restore workflow, live-link state machine), `ConfigBlock`, `FirmwareImage` (patch + safety validation), `DfuService`
(embedded dfu-util), `DeviceMonitor` (HID + PnP via P/Invoke), `LiveProtocol`, `CalibrationWizard`, `StockFirmware`, `SelfTest`. The firmware image and
`dfu-util-static.exe` are embedded resources, so **rebuild the app after every firmware build**.

- Live editing: 70 ms debounce before a push, autosave to flash after 1.5 s quiet, a restart bar only for identity changes.
- Flash success rule (`DfuService.Interpret`, unit-tested with real logs): this controller usually resets before dfu-util can read the final status
  (`LIBUSB_ERROR_PIPE/IO`), which is a normal success; a rejection shows `dfuERROR` / `status(7)` after "Download done.". The app then verifies by waiting for
  the custom firmware and comparing its reported config CRC.
- Serial display: read from the controller when it runs KishiDS firmware (`HidD_GetSerialNumberString`) and from the Windows device instance ID while the
  stock firmware is connected.
- **Performance note:** feature reports go over a second HID handle. Windows serialises I/O on a synchronous handle, so sharing the reader's handle made
  every telemetry read wait behind the blocking input read (about 17 ms each, which showed up as laggy button visuals).

### Developer / automation flags (`KishiDS.exe ...`)

| Flag | Purpose |
|---|---|
| `--selftest <log>` | Offline checks, including "C# defaults + CRC == firmware-built block" |
| `--status <log>` | Report what the monitor sees (device state, serial, telemetry) |
| `--live <log> [profile.json] [--save] [--reboot]` | Hardware test of live editing: push, read back, optionally save and reboot |
| `--apply <log> [profile.json]` / `--restore <log>` | Run the real flash flows headless (needs the bootloader; auto-confirms) |
| `--import-stock <file.bin\|.apk> <log>` | Import Razer's original v2.70 image |
| `--screenshot <Page\|Setup1..3> <png> [w h] [--phase ...]` | Render a page off-screen |
| `--demo` (`--stress`) | Simulated controller (with random input on every tick) |
| `--perf <log> [Page\|Nav]` | Measure UI responsiveness and device read rates for 6 s |
| `--setup`, `--stockdir <dir>`, `--light`, `--dark` | Force the setup guide, relocate the stock store, force a theme |

## Pitfalls

- Flash page `0x0800F800` is off limits. Also never "fix" the factory serial: it is the only copy.
- A firmware change that alters the config block changes the image id, so a controller starts from default settings once after being flashed.
- Windows keeps a stale "Unknown USB Device (Device Descriptor Request Failed)" ghost entry on a hub port; it is unrelated to the Kishi.
- dfu-util usually ends with `LIBUSB_ERROR_PIPE` after a good flash (the controller resets first). Judge success by "Download done." and the absence of `dfuERROR`.
- Under some Windows setups gcc cannot create temp files inside devkitPro's `make`; pass `CFLAGS=-pipe` (see `BUILDING.md`).

## Known unknowns

- The purpose of the PB14 / PC9 outputs that stock drives high at boot (replicated, harmless so far).
- "Experiment B": an inert byte edit inside stock code once stopped the stock image booting. Not understood, and no longer relevant to the custom firmware.
