# KishiDS for Android

The phone version of [KishiDS](../README.md): remap buttons, tune sticks and triggers, set the LED, calibrate, and flash firmware on a Razer Kishi V1,
straight from the phone it is clamped to. It mirrors the Windows app page for page (Overview, Buttons, Sticks, Triggers, D-pad, Lighting, Calibration,
Identity, Firmware) and speaks the same protocol, so profiles saved by either app load in the other.

Built for how the Kishi is actually held: **landscape** gets the desktop's side navigation and two-column pages (with the system bars hidden on short
screens); **portrait** gets a bottom bar. Dark and light themes use the desktop palettes. Plain Android framework views, no libraries.

## Build

Needs JDK 17 and the Android SDK (platform `android-34`, build-tools 34). No Gradle, no Android Studio.

```powershell
.\build.ps1            # -> dist\KishiDS.apk   (min Android 7.0)
.\build.ps1 -Install   # also installs it with adb
.\test\run.ps1         # JVM self-test of the core (no phone needed)
```

The first build creates a throw-away signing key in `keystore\` (git-ignored). Use `-Keystore`, `-KeyAlias`, `-KeyPass` for your own release key.
The firmware image `firmware-research\ds4-firmware\kishi_ds4.bin` is packaged into the app, so rebuild after every firmware build. The config schema is
generated for Java by `firmware-research\tools\gen_config.py` (same single source of truth as the firmware and the Windows app); the controller artwork
is generated from `SVG\` by `tools\make_art.py`; icons by `tools\make_icons.py`.

Developer launch extras (`adb shell am start -n com.electrotamp.kishids/.ui.MainActivity ...`): `--ez demo true` runs with synthetic controller data and
no USB, `--es page <Name>`, `--ei guide <1-3>`, `--es phase waiting|ready|flashing|done|failed`, `--ez light true`.

## How it talks to the controller

Android is the USB host and has no HID feature-report API, so the app claims the Kishi's interface (detaching Android's HID driver while the app is in
front) and sends the HID class requests itself: `GET_REPORT`/`SET_REPORT` on feature report `0xAB` (telemetry) and `0xAC` (live editing), exactly what the
Windows app does through `HidD_GetFeature`/`HidD_SetFeature`, plus the interrupt-IN input report for the live stick/trigger previews. Settings apply
instantly and are saved to the controller's flash after a quiet moment, so they work in every game. A few seconds after the app leaves the screen it hands
the controller back to Android's HID driver.

Android asks for USB access per device each time the controller (re-)enumerates: once on connect, and again after a flash.

## How Razer's own app updates the firmware (and what we do the same)

Decompiling Razer's Android app (`com.razer.mobilegamepad.en`, library `com.razer.usbdfu`) shows a plain USB DFU update, with nothing proprietary:

1. The user must put the controller in *Firmware Update mode* by hand (detach from the phone, hold the button combination, reattach). The app only
   detects the bootloader (`27F8:0BC0`, product string contains "bootloader") and offers the update; it cannot reboot the controller into it.
2. It claims interface 0 (forcing the kernel driver off) and sends standard DFU control requests: `GET_STATUS`, `CLEAR_STATUS`, then `DNLOAD` in 1,024-byte
   blocks (block number in `wValue`, alternate setting 0), then a zero-length `DNLOAD` to start the manifest. No signing, container or encryption.
3. It picks the image by the controller's bcdDevice firmware version (2.70 for this unit's family).

KishiDS does the same transfer (`core/DfuFlasher.java`), additionally polling `GET_STATUS` until the bootloader is idle after each block (as `dfu-util`
does), checking every status, and treating the controller's reset after manifest as success. The same user steps apply, so the Firmware page walks
through them. Razer's original image is not bundled: with Razer's Kishi app installed, **Find it for me** reads it straight from the installed package
(verified by SHA-256); otherwise pick the `.apk` / `.bin` file.

## Not yet verified on a phone

The core (config block, live protocol, telemetry, profiles, stock import, calibration, DFU against a simulated bootloader) is covered by `test\run.ps1`,
and the UI was exercised in an emulator in demo mode. Not yet run against a real controller: USB permission and interface claim on a real phone, the
control transfers, input-report streaming, and handing the controller back to Android afterwards (it uses a USB reset, which the platform may refuse; if
games stop seeing the controller after the app was open, unplug and replug it). The app keeps a technical log (Firmware page) for diagnosing exactly this.
