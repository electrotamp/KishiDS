# Kishi 0290 DS4-style firmware

Replacement application for the Razer Kishi V1 (RZ06-0290, STM32F072-class).  It enumerates as a wired
DualShock 4 (`054C:05C4`) and reports the real Kishi controls.

## Key facts (2026-10-06)

- **Link base is `0x08003000`** (not `0x08009000` as earlier notes claimed).  The vendor bootloader
  (`GV Bootloader V1.0.2`) occupies `0x08000000-0x08002FFF` and rejects images whose vector table points
  outside the app region with DFU status 7.  RAM starts at `0x20000100` (`0x20000000-0xFF` is left free).
- Clocks: HSI48 -> PLL (/2 x2) = 48 MHz as SYSCLK, USB from HSI48 + CRS auto-trim (mirrors stock; the 12 MHz crystal
  assumption in early notes was wrong - stock never enables HSE).
- Pin map, ADC channels and calibration format: see `../BOARD_MAP.md`.
- Never overwrite flash page `0x0800F800` (stock settings/calibration).

## Gotchas learned the hard way

- **Never touch an endpoint before the host configures the device.** USB packet memory is plain SRAM with
  random contents after a power-on; `usbd_ep_write_packet(0x81, ...)` before `SET_CONFIGURATION` writes through a
  garbage buffer pointer and hangs/faults.  It "worked" right after a DFU flash (RAM still held harmless leftovers)
  and failed on every cold plug-in.  The firmware gates endpoint writes on a `configured` flag.
- Stock runs HSI48 -> PLL (/2 x2) = 48 MHz with USB from HSI48 and never enables the HSE crystal; do the same
  (plus CRS auto-trim).  The bootloader hands over a clean reset state (HSI only, nothing enabled).
- `DIAG=1` builds keep a flash black-box boot log at `0x0800E000` (one 56-byte slot per boot; read with
  `../tools/diag_log.py`).  It survives DFU re-flashes and is the way to debug cold-boot failures (USB is dead when
  the firmware hangs).  Blue LED (PB4, active-low) blinks ~3 Hz in DIAG builds as a liveness heartbeat; release holds it on steadily.

## Build

Prerequisites: [devkitARM](https://devkitpro.org/) (`arm-none-eabi-gcc` on `PATH`, or set `DEVKITARM`), Python 3, and libopencm3 built once for STM32F0
(the pinned submodule, `git submodule update --init`):

```bash
make -C ../third_party/libopencm3 TARGETS=stm32/f0 CFLAGS=-pipe
```

(`CFLAGS=-pipe` avoids gcc temp files, which fail under some Windows setups.) A clean clone built this way reproduces the committed
`kishi_ds4.bin` byte for byte (SHA-256 `898615797ebd5cb0858d7831f5b86b705cff6d02796993d7c2bd37ad6d109897`).

`./build.sh`:

| Command | Output | Purpose |
|---|---|---|
| `./build.sh` | `kishi_ds4.bin` | release: clean DS4 reports |
| `DIAG=1 ./build.sh` | `kishi_ds4_diag.bin` | raw ADC + button mask in report bytes 13-26, boot-log + register snapshots via feature reports `0xF1`/`0xB0`/`0xAC`/`0xF0`/`0xAB`, PB4 heartbeat |

`main_stage1_neutral.c` is the old neutral-input test.  `kishi_ds4_stage1*.bin` are historical and not included in this repository (the first two were
mis-linked at `0x08009000` and rejected).

## Flash / recover

Enter DFU: hold **Y + B + Right Function** while plugging in (`27F8:0BC0`).

```
tools\dfu-util\dfu-util-static.exe -d 27f8:0bc0 -a 0 -D ds4-firmware\kishi_ds4.bin
```

Restore stock: same command with Razer's v2.70 image (not in this repo; extract it from Razer's Android app, or let KishiDS import it).
Expected SHA-256 `AA915363C858E5A06FB1AD15A82D059D50FD22A625D0663A3CE99FCAB24F69BE`.  The bootloader's button combo
always works regardless of the application.

## Host tools

`../tools/diag_read.py` (run with Python 3.12 that has `hid`): `live`, `capture`, `sweep`, `cal` against the DIAG build.

## Reference

DS4 descriptor/feature data: Android CTS wired DualShock 4 capture
(https://android.googlesource.com/platform/cts/+/2b3c2576be0/tests/tests/hardware/res/raw/sony_dualshock4_usb_register.json).
