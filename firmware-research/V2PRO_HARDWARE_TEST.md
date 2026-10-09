# Kishi V2 Pro: first hardware test

Step-by-step plan for the first session with a real Kishi V2 Pro (RZ06-0458), on the Mac. Everything so far was built
from Razer's app and firmware and tested only in emulation; this session checks the assumptions in a safe order.

**Rule for the whole session:** each phase only starts when the previous one ended as expected. If anything differs
from what is written here, stop, copy the full terminal output, and do not try the next phase. Nothing up to phase 3
writes to the controller's flash, and phase 3 only writes Razer's own firmware.

| Phase | Writes flash? | Can it leave the controller unusable? |
|---|---|---|
| 0. Preparation | no | no |
| 1. Read-only queries | no | no |
| 2. Enter and leave the bootloader | no | only stuck in the bootloader; phase 3 recovers |
| 3. Recovery: reflash Razer's 2.2 | yes (Razer's image) | in theory, if the bootloader rejects it; then Razer's app is the fallback |
| 4. Signature test (Razer's 2.2 with one byte changed) | yes | if rejected or not booting: phase 3 restores |
| 5. Our firmware | yes | if it neither runs USB nor reaches the bootloader (see phase 5); three ways back are built in |

## 0. Preparation (without the controller)

Run everything from `firmware-research/`.

1. Install the HID library: `pip install hidapi`.
2. Download Razer's 2.2 firmware and check it is the expected file:
   ```bash
   mkdir -p device-backups
   curl -o device-backups/v2pro_02.02.hex https://mobileapp-assets.razerzone.com/BiancaFw/T1/02.02.00.00/firmwareHex.hex
   shasum -a 256 device-backups/v2pro_02.02.hex
   ```
   Expected: `e1d440a03cef8668aa465f900a95bba5666e32c5a9a47acbeeebd3b7487a3619`. (`device-backups/` is git-ignored.)
3. Check the recovery tool accepts it, offline:
   ```bash
   python tools/v2pro_dfu.py plan device-backups/v2pro_02.02.hex
   ```
   Expected: `93184 bytes at 0x8000..0x1ebff, 1456 blocks of 64`, and the packet lines.
4. Optional but recommended: keep an Android phone with Razer's app (Razer Cortex / Nexus) at hand. If everything else
   fails, it is Razer's own way to update the controller.
5. Connection: the Kishi plugs into the phone with its USB-C plug. To reach the Mac, use a USB-C extension cable
   (male to female) or connect it directly if it fits. The pass-through charging port is not a data port to the host.
6. Close Razer's app on any phone and don't connect the controller to a phone during the session.

## 1. Read-only queries (nothing is written)

1. Connect the controller and list it:
   ```bash
   python tools/v2pro_probe.py list
   ```
   Expected: one or more lines `1532:0717 Kishi V2 Pro (HID mode)` (or `0718` in XInput mode), with interface numbers.
   If nothing appears: try another cable/adapter, then run `system_profiler SPUSBDataType` and send me the Razer entry.
2. Identity and firmware version:
   ```bash
   python tools/v2pro_probe.py info
   ```
   Expected: `status=ok` lines and a decoded firmware version (2.2.x if it is up to date) and serial number.
   If every line says `status=failed` or nothing answers, repeat with `--tid 0x3F`, then `--tid 0xFF`, and note which one
   answers. **That transaction ID is a result to keep.**
3. Buttons and axes (Razer's factory read, still read-only):
   ```bash
   python tools/v2pro_probe.py buttons 120          # add --tid if step 2 needed it
   ```
   Press each button once, alone, in this order, and keep the output: A, B, X, Y, D-pad up, down, left, right, L1, R1,
   L3, R3, the two programmable buttons (left one first), View, Menu, the Razer/Nexus button, Share/screenshot. Then
   move the **left** stick fully right, back to centre, fully up, centre; the **right** stick the same; then press L2
   and R2 fully.
   This confirms or corrects the hypothetical button names in `BOARD_MAP_V2PRO.md` and tells which report axes belong to
   the left stick.
4. Optional: switch the controller to its other input mode (if you know how, e.g. from Razer's app) and repeat `list`
   and `info`, to record the other PID.

**Send me:** the output of `list`, `info` and `buttons`, and which `--tid` worked. With that I fix the board tables
before anything else.

## 2. Enter and leave the bootloader (nothing is erased)

Only continue if phase 1 answered and phase 0 step 3 passed.

1. Enter Razer's bootloader by software (`SetDeviceMode 01 00`, what Razer's app sends before an update):
   ```bash
   python tools/v2pro_dfu.py enter
   ```
   It shows the firmware version, asks you to type `yes`, sends the command and waits up to 15 s for `1532:110E`.
   Expected: `found 1532:110e (Razer bootloader (Recovery))` and a `GetDFUDeviceInformation:` line.
   The controller's buttons will not work while it is in the bootloader; that is normal.
2. Read the bootloader again, read-only:
   ```bash
   python tools/v2pro_probe.py dfuinfo              # add --tid if phase 1 needed it
   ```
   **Keep this output**: the meaning of those bytes is unknown and it is the first real look at the bootloader.
3. Leave it without flashing:
   ```bash
   python tools/v2pro_dfu.py exit
   ```
   Expected: `Back in the application.` and `1532:0717` again. Check with `python tools/v2pro_probe.py info`.

**If `exit` says "Still in the bootloader"**: the bootloader does not return to the application on `DFUExit` alone.
Do not unplug repeatedly or try other commands: go to phase 3, which rewrites Razer's firmware and exits.

**If the bootloader never appears after `enter`**: unplug and replug. If `list` shows the normal `0717` again, the
software entry is not accepted on this firmware: stop and send me the output.

## 3. Recovery: reflash Razer's 2.2 (writes Razer's own image)

Do this phase once on purpose, even if phase 2 went well: it proves the recovery route before any image of ours is
ever tried. Keep the controller connected and powered for the whole step (about a minute or two).

1. If not already there, enter the bootloader: `python tools/v2pro_dfu.py enter`.
2. Flash Razer's firmware:
   ```bash
   python tools/v2pro_dfu.py flash device-backups/v2pro_02.02.hex
   ```
   It checks the SHA-256, shows the bootloader information, asks for `yes`, erases `0x8000..0x1ebff`, writes 1456
   blocks, reads all of them back, and exits.
   Expected: progress for `Program` and `Verify`, then `Restored.` and the controller back as `1532:0717`.
3. Confirm: `python tools/v2pro_probe.py info` (firmware 2.2) and `buttons` (it still works as before).

**Notes to keep:** any `warning:` line about echoes (the reply layout differs from what I assumed; harmless if Verify
passed), and how long each stage took.

**If it fails:**
- `FAILED` before `Erased.`: nothing was written. Send me the output; run `exit` if it is still in the bootloader.
- `FAILED` after `Erased.` (during Program): the tool sends `DFUAbort` and leaves it in the bootloader. Run `flash`
  again once. If it fails the same way, stop and send me everything.
- Only `DFUVerify` fails: the read-back layout may be my mistake rather than the flash. Try `exit`; if the controller
  comes back and `info`/`buttons` work, the flash was fine.
- As a last resort, Razer's Android app: it has code for a controller sitting in the bootloader (`1532:110E`) and
  should offer to update it, though that path has not been tried.

## 4. Signature test (after phases 1–3 passed)

Razer's 2.2 with **one byte** changed: the "K" of the USB product string, so the controller should come up as
"Razer Xishi V2 Pro". This decides whether custom firmware is possible at all. What was checked offline: the change
is one literal byte of the compressed data (`0x1E6EA`); emulating the start-up of both images, the decompressed RAM
differs in that one byte only and the boot runs the same instructions, so no self-check of the image showed up.

1. Make the test image from the official file (it stays local, in the git-ignored folder):
   ```bash
   python tools/v2pro_dfu.py sigtest device-backups/v2pro_02.02.hex device-backups/v2pro_sigtest.hex
   ```
   Expected SHA-256: `be5c248d34d489fdb9084c50f9a6aa333079f80736087486ac7d067e26b169d1`. The file differs from Razer's in
   one line.
2. Enter the bootloader (`python tools/v2pro_dfu.py enter`), then flash it:
   ```bash
   python tools/v2pro_dfu.py flash device-backups/v2pro_sigtest.hex --signature-test
   ```
   At the end the tool prints a `RESULT:` line.
3. Read the result:
   - **`the modified image booted`** (and `v2pro_probe.py list` shows `'Razer Xishi V2 Pro'`): the bootloader checks
     no signature. Custom firmware is possible. Check `buttons` still works.
   - **`FAILED` during erase/program/verify, or `did not come up`**: the bootloader probably rejects modified
     images. Restore at once with phase 3 (`flash device-backups/v2pro_02.02.hex`) and send me the output.
   - **`unclear`**: the system may be caching the old name. Unplug, replug, and run `list` again.
4. **Always finish by restoring Razer's image** (phase 3), whatever the result.

## 5. Our firmware

Only after phase 4 succeeded and the button table was corrected from phase 1 (both done on 2026-10-07). Our build
has three ways back into Razer's bootloader, all doing what Razer's firmware does on `SetDeviceMode 01 00`
(`v2pro-firmware/bootloader.h`): KishiDS live command 7 over USB, **View + Menu held while plugging in** (checked
before clocks and USB), and any fault. `v2pro_dfu.py flash --custom` refuses builds without them.

**The one risk left:** the boot flag (`0xAAAAAAAA` at `0x20017FFC`, then reset) is read from Razer's application
code; the bootloader side has never been seen. Razer's `SetDeviceMode` takes exactly that path and does land in the
bootloader, so it should work, but if it did not, a working-but-unflashable controller could only be recovered with
the LPC55's own ROM ISP or a debug probe (opening the controller). Step 3 tests it first, while USB still works.

0. Build and check, without the controller:
   ```bash
   cd v2pro-firmware && ./build.sh && python tests/emu_test.py && python tests/emu_persist_test.py \
     && python tests/emu_bootloader_test.py && cd ..
   python tools/v2pro_dfu.py plan v2pro-firmware/kishi_v2pro_ds4.hex
   ```
   Expected: `0 failure(s)` three times, and `plan` naming it "KishiDS V2 Pro firmware" at `0x8000..0x1e1ff`.
1. `python tools/v2pro_dfu.py enter` (from Razer's firmware, as in phase 2).
2. `python tools/v2pro_dfu.py flash v2pro-firmware/kishi_v2pro_ds4.hex --custom`. It ends with a `RESULT:` line:
   - **`our firmware booted`**: go on with step 3.
   - **`back in Razer's bootloader`**: it faulted at start-up. Restore (phase 3) and send me the output.
   - **`nothing enumerated`**: it runs without USB. Unplug, hold **View + Menu**, plug back in while holding them;
     `v2pro_probe.py list` should show `1532:110e`. Then restore (phase 3).
3. **Prove the way back first**, before anything else: `python tools/v2pro_dfu.py enter` (now it sends live command 7).
   Expected: `found 1532:110e`. Then `python tools/v2pro_dfu.py exit`: our firmware starts again (a DS4).
4. The other way back: unplug, hold View + Menu, plug in. Expected: `list` shows `1532:110e`. Then `exit` again.
5. With our firmware running:
   - Windows shows a "Wireless Controller"; `joy.cpl` (Game Controllers) shows every button and axis.
   - `python tools/v2pro_probe.py raw 90`: move the **left** stick fully right, centre, fully up, centre; the **right**
     stick the same; press L2 and R2. Each line shows the six raw values (named with their pins); this settles which
     pin is X and which is Y, and the direction, for `board_v2pro.c`.
   - The KishiDS app's live view and calibration work on it too (it talks to any DS4 running our firmware). Do not use
     its V1 firmware pages on this controller.
6. Finish by restoring Razer's 2.2 (`enter`, then phase 3) unless we decide to keep ours.

**Send me:** every `RESULT:` line, the output of steps 3 and 4, and the `raw` capture with the order of movements.

## What to send me after the session

- `list`, `info` (which `--tid`), `buttons` output, and the order in which buttons and axes were pressed.
- `enter`, `dfuinfo` and `exit` output.
- `flash` output, including warnings and roughly how long it took.
- The signature test's `RESULT:` line and `list` output.
- Anything that differed from "Expected", with the exact output.
