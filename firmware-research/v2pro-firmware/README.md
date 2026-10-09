# Kishi V2 Pro DS4 firmware

Replacement application for the Razer Kishi V2 Pro (RZ06-0458, NXP LPC55xx, see `../KISHI_V2_PRO.md` and
`../BOARD_MAP_V2PRO.md`) that will enumerate as a wired DualShock 4, like the V1 firmware in `../ds4-firmware`.

**Status: runs on hardware as a wired DualShock 4 (2026-10-07..09).** It enumerates as `054C:05C4` on Windows in
~0.6 s; buttons, sticks (own default calibration), triggers, the RGB LED, rumble and saved settings work on the
controller, and the three ways back into Razer's bootloader too. Not yet tried: an iPhone, the audio codec /
HyperSense. `v2pro_dfu.py flash --custom` flashes this build only if it passes its checks (including the recovery
marker). `./build.sh diag` builds a variant that reports start-up problems through Razer's bootloader (`diag.h`).

| Part | State |
|---|---|
| Startup, vector table, linker script (app at `0x8000`, stack in SRAMX like Razer's) | done |
| Button pins, IOCON pull-ups, scan → DS4 report through the shared `report.c` | done; every name confirmed on hardware |
| Ways back into Razer's bootloader (Razer's own flag + reset): live command 7, View + Menu at power-on, any fault | done (`bootloader.c`), emulation-tested; the flag is read from Razer's code, first hardware test in phase 5 |
| Shared logic from `../ds4-firmware` (`config.c`, `report.c`, `live.c`), compiled unchanged | done |
| Saved settings (KishiDS live SAVE/ERASE) in their own page at `0x1E000`, read only through the flash controller | done (`flash.c`, `persist.c`), emulation-tested incl. power cuts |
| ADC (LPADC0): Razer's set-up and channels, NXP's calibration, one chained scan per trigger, non-blocking | done (`adc.c`), emulation-tested pin → report byte |
| Which stick is left/right and X/Y per pin | left/right confirmed on hardware; X/Y per pin and direction still to confirm with `../tools/v2pro_probe.py raw` (telemetry 0xAB) |
| Stick/trigger calibration | V1 defaults until calibrated with the KishiDS wizard (triggers fall when pressed, like the V1: confirmed in Razer's code) |
| Clocks: CPU 48 MHz (FRO_HF / 2), flash wait states as Razer, core voltage untouched | done (`clock.c`), emulation-tested |
| USB0 full speed, crystal-less (FRO trimmed by SOF), TinyUSB, DS4 enumeration identical to the V1 | done (`usb_ds4.c`), host-tested |
| Reports: input 0x01, fixed DS4 features (shared `ds4_usb.c`), KishiDS telemetry 0xAB, live editing 0xAC | done, host-tested |
| Outputs Razer drives at start-up: P1_18, P1_12, P1_9 (one supplies the sticks/triggers) | done, as Razer |
| RGB LED (P0_2/P0_3/P1_25, SCTimer PWM): V1 LED modes, DS4 lightbar colour from the host | done (`led_v2pro.c`), confirmed on hardware |
| Rumble: two DRV2605L (I2C 0x5A on FLEXCOMM1 = left / strong, FLEXCOMM4 = right / weak), LRA, RTP mode | done (`haptics_v2pro.c`, `i2c_lpc55.c`), confirmed on hardware; level 255 = 80 % of full scale |
| Audio codec / HyperSense (I2C 0x38 on FLEXCOMM3, I2S) | **TODO** |
| Saved settings on hardware | done: needs Razer's brown-out set-up first (`flash_power_init`); survives power cycles; a bootloader visit clears it |
| M1, M2, Share | read, but dropped: the shared config schema only knows the V1's 15 buttons |

## Layout

| File | What |
|---|---|
| `startup.c`, `v2pro_app8000.ld` | Vectors, reset handler, memory map (`0x8000`–`0x1DFFF` code, `0x1E000` saved page); faults go to the bootloader |
| `bootloader.c`, `bootloader.h` | Restart into Razer's bootloader the way Razer's firmware does, and the recovery marker `v2pro_dfu.py` checks |
| `lpc55.h` | The handful of registers used, as addresses (cross-checked against Razer's firmware) |
| `board.h`, `board_v2pro.c` | Pin tables, `board_read_buttons()`, mapping onto the shared Kishi button bits |
| `flash.c`, `flash.h` | Flash controller: erase/program a 512-byte page and fault-free reads (Razer's driver sequences) |
| `persist.c`, `persist.h` | Saved-settings record: safe boot-time read, save/erase with read-back verification |
| `clock.c`, `clock.h` | 48 MHz CPU from the 96 MHz FRO; USB0 power, reset, 48 MHz clock and device mode |
| `adc.c`, `adc.h` | LPADC0: 12 MHz clock, offset/gain calibration, chained scan of the six analog pins |
| `usb_ds4.c`, `usb_ds4.h` | TinyUSB callbacks: DS4 descriptors, endpoints 0x81/64 and 0x01/32, all report paths |
| `tusb_config.h`, `fsl_device_registers.h` | TinyUSB settings, and the few NXP definitions its LPC55 driver needs (instead of the whole MCUXpresso SDK) |
| `main.c` | Clocks, pins, config, USB; loop: scan → `report_build()` → `usb_ds4_send()`, live editing service |
| `libc/` | `memcpy`/`memset`/`memcmp` and a bare `inttypes.h`, so the build needs no C library |
| `tests/emu_test.py` | Boots the built image in Unicorn: clock/USB/ADC bring-up registers, pins, the DS4 report for every button and axis |
| `tests/emu_persist_test.py` | Save, reboot, erase, power cut after erase and mid-program, address guards; any bus read of a bad page fails it |
| `tests/emu_bootloader_test.py` | The three ways into Razer's bootloader (combo before the clock switch, faults, live command 7), and that normal boots and reboots leave the flag alone |
| `tests/test_usb_host.c` | Real TinyUSB + `usb_ds4.c` on the Mac with a fake bus driver: full enumeration and every report path |
| `tests/test_adc_gain.c` | The integer gain calibration against NXP's float algorithm for all 65,536 inputs (never more than 1 LSB apart) |

## Build and test

```bash
git submodule update --init firmware-research/third_party/tinyusb   # TinyUSB 0.21.0, pinned
./build.sh                         # needs only arm-none-eabi-gcc (Homebrew's works: the build is freestanding)
python tests/emu_test.py           # needs unicorn (../../requirements.txt); models clocks, USB bring-up and the ADC
python tests/emu_persist_test.py   # same harness, plus a flash controller model
python tests/emu_bootloader_test.py
tests/run_host_tests.sh            # host C compiler: USB and ADC gain tests
```

`build.sh` stamps the config block's CRC (`../tools/finalize_image.py`, as the V1 build does) and refuses an image whose
vectors point outside it or that would extend past Razer's own image span (`0x8000`–`0x1EBFF`). The `.hex` is contiguous:
`v2pro_dfu.py` rejects images with holes.

## LPC55 pitfalls to keep in mind

- **A bus read of a flash page that is erased-but-unprogrammed, or half-programmed, faults** (ECC). A fault at boot would
  repeat on every boot (the fault handler would at least drop into Razer's bootloader, but the controller would be
  unusable until re-flashed). So the settings
  page is only ever read through the flash controller (`flash_read_safe`, command 3, which reports such a page as an
  error), `config.c` takes the record from that copy (`KCFG_SAVED_VIA_BOARD`), and "erase" programs the page to 0xFF
  instead of leaving it erased. `emu_persist_test.py` fails if anything reads the page directly.
- Flash is driven by registers with the same sequences as the driver in Razer's firmware (not NXP's ROM API, whose
  layout depends on the chip revision). Writes are only accepted for pages inside our image span (`0x8000`–`0x1EBFF`);
  where Razer keeps its own settings is unknown and never touched.
- From the V1: never write the USB IN endpoint before `SET_CONFIGURATION`, and always test a cold plug, not just after a flash.

## USB: how it was built and what is still unproven

- **Stack: TinyUSB 0.21.0** (MIT, submodule) with its `lpc_ip3511` driver. NXP's SDK is not used: `fsl_device_registers.h`
  supplies the five definitions the driver needs.
- **Clock and power sequences** follow NXP's `fsl_clock` and TinyUSB's LPC55 board code, and every register they write
  also appears in Razer's own start-up (traced by emulating firmware 2.2), with Razer's flash wait-state value.
  One deliberate difference: Razer raises the core voltage (PMC, from per-chip factory trims) to run at 96 MHz; we
  leave the voltage alone and run at 48 MHz. The emulation test checks that no other PMC register is written.
- **DS4 identity** is the V1's: same device and configuration descriptors (including the 32-byte OUT endpoint), and the
  report descriptor and fixed feature reports come from `../ds4-firmware/ds4_usb.c`, the one module both boards use.
- **Unproven until hardware:**
  - crystal-less USB at 48 MHz CPU on this board;
  - no VBUS sensing (P0_22, the LPC55's USB0_VBUS pin, is used as a button by Razer, so the port connects unconditionally);
  - the state Razer's bootloader leaves the chip in when it jumps to `0x8000`;
  - the serial number and the DS4 Bluetooth address (features 0x12/0x81) come from the chip's factory UUID at `0x9FC70`
    (this is a 640 KB LPC55; `0x3FC70` does not answer), so iOS tells two controllers apart.
