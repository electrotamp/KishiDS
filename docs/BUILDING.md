# Building KishiDS

This guide takes you from a fresh clone to a working `KishiDS.exe`, a rebuilt controller firmware, and a flashed controller. The build steps (sections 3 and 4) were run from a clean clone on Windows 11, and the version numbers below are the ones they were tested with. The flashing steps (section 5) describe the same flow the app uses; the driver step (5.1) is standard Zadig usage.

There are two things to build, and you usually only need the first:

| What | Output | Needed for |
|---|---|---|
| **The app** (`KishiDS/`) | `KishiDS\publish\KishiDS.exe` | Everything. It embeds the firmware, so you can build and use it without ever compiling firmware. |
| **The firmware** (`firmware-research/ds4-firmware/`) | `kishi_ds4.bin` | Only if you change the firmware. A prebuilt image is committed to the repo. |

## 1. What you need

| Tool | Version tested | For | Notes |
|---|---|---|---|
| Windows | 11, 64-bit | the app (WPF) | The app is Windows-only. |
| [Git](https://git-scm.com/download/win) | 2.47 | cloning | Must support submodules (any modern Git does). |
| [.NET 9 SDK](https://dotnet.microsoft.com/download/dotnet/9.0) | 9.0.317 | the app | The SDK, not just the runtime. WPF ships with it on Windows. |
| [devkitPro / devkitARM](https://devkitpro.org/wiki/Getting_Started) | gcc 16.1.0 | firmware | The installer also gives you an MSYS2 shell with `bash` and `make`. |
| Python 3 | 3.12 | firmware build scripts | Must be on `PATH` **inside the shell you build from**. The devkitPro MSYS2 used for testing already had one; if yours does not, install Python and add it to `PATH`. |
| A host C compiler (gcc or clang) | clang 22 | firmware unit tests (optional) | Any of gcc, clang or `x86_64-w64-mingw32-gcc`. |
| `pip install unicorn` | | ARM-emulator tests (optional) | Python 3.12 with the `unicorn` package. |

You do **not** need a Kishi to build anything. You need one only to flash and try it.

## 2. Get the source

```bash
git clone --recurse-submodules https://github.com/electrotamp/KishiDS.git
cd KishiDS
```

If you already cloned without `--recurse-submodules`:

```bash
git submodule update --init
```

This fetches [libopencm3](https://github.com/libopencm3/libopencm3) (pinned to a specific commit) into `firmware-research/third_party/libopencm3`.
You only need it for the firmware; the app build ignores it.

## 3. Build the app (the quick path)

From the repository root, in PowerShell or any shell:

```powershell
cd KishiDS
dotnet publish -c Release -r win-x64 --self-contained false -p:PublishSingleFile=true -o publish
```

The result is a single file, **`KishiDS\publish\KishiDS.exe`** (about 2.6 MB). It needs the
[.NET 9 Desktop Runtime](https://dotnet.microsoft.com/download/dotnet/9.0) on the machine that runs it. It embeds the committed
`kishi_ds4.bin` and `dfu-util-static.exe`, so there is nothing else to ship.

The build prints one C# nullability warning (`CS8602` in `AppModel.cs`); it is known and harmless.

### Check it

The app has an offline self-test that needs no controller. It is a GUI program, so it writes its results to a file (`Start-Process -Wait` makes PowerShell wait for it to finish):

```powershell
Start-Process -Wait .\publish\KishiDS.exe -ArgumentList '--selftest','selftest.txt'
Get-Content selftest.txt | Select-Object -Last 3     # expect: ALL PASSED
```

To look at the UI without a controller, run it in demo mode (simulated input, shows a sample serial number):

```powershell
.\publish\KishiDS.exe --demo
```

## 4. Build the firmware

Only needed if you change anything under `firmware-research/ds4-firmware`.

### 4.1 Open the right shell

`build.sh` is a bash script. Use either the **MSYS2 shell that comes with devkitPro** (start menu: *devkitPro > MSYS2*), or **Git Bash**. In both,
`arm-none-eabi-gcc` must be reachable:

- In the devkitPro MSYS2 shell it normally already is.
- In Git Bash, put devkitARM on `PATH` yourself. Both the libopencm3 `make` in 4.2 and `build.sh` in 4.3 need `arm-none-eabi-gcc` to be found:

  ```bash
  export DEVKITARM=/c/devkitPro/devkitARM        # adjust to where you installed devkitPro
  export PATH="$DEVKITARM/bin:$PATH"
  ```

Check:

```bash
arm-none-eabi-gcc --version      # first line should mention devkitARM
python --version                 # must work in this shell
```

### 4.2 Build libopencm3 (once)

```bash
make -C firmware-research/third_party/libopencm3 TARGETS=stm32/f0 CFLAGS=-pipe
```

This produces `firmware-research/third_party/libopencm3/lib/libopencm3_stm32f0.a`. `CFLAGS=-pipe` makes gcc stream between its stages instead of writing
temporary files; without it, some Windows setups fail with `Cannot create temporary file in C:\WINDOWS\: Permission denied`.

You only repeat this if you delete the library or update the submodule.

### 4.3 Build the firmware

```bash
cd firmware-research/ds4-firmware
./build.sh
```

You should end with something like:

```
kishi_ds4.bin: 15340 bytes, SP 0x20004000, reset 0x8005b51, all vectors in image: True
sha256 898615797ebd5cb0858d7831f5b86b705cff6d02796993d7c2bd37ad6d109897
```

`build.sh` compiles each source file with `arm-none-eabi-gcc`, links at the vendor application base `0x08003000`, converts to a raw binary, and runs
`tools/finalize_image.py`, which stamps the config block's CRC and checks that the vector table sits inside the application region.

**Reproducibility:** the committed `kishi_ds4.bin` (hash above) was built with devkitARM (gcc 16.1.0) before the DS4 descriptor and
fixed feature reports moved from `main.c` into the shared `ds4_usb.c` (also used by the Kishi V2 Pro firmware). The current source therefore
no longer rebuilds it byte for byte, even with that devkitARM: the code moved, the bytes the controller serves did not. That was checked by
running the request handler of both builds in an emulator for the report descriptor and every report ID (all replies identical), plus the
host and ARM-emulator tests below. The next release should rebuild `kishi_ds4.bin` with devkitARM, update the hash above, and rebuild the
app, which embeds the image. A different compiler version may also produce a different binary that behaves the same; the hash then differs,
which is expected.

**macOS / Linux:** the [Arm GNU Toolchain](https://developer.arm.com/downloads/-/arm-gnu-toolchain-downloads) (it includes newlib, which
libopencm3 and the firmware need; Homebrew's `arm-none-eabi-gcc` does not) works too: put its `bin` on `PATH`, build libopencm3 as in 4.2,
then `./build.sh`. `build.sh` uses `python3` when `python` is not installed.

Other build modes:

| Command | Result |
|---|---|
| `./build.sh clean` | Remove build output (`*.o`, `*.elf`, `*.map`, `kishi_ds4*.bin`) |
| `DIAG=1 ./build.sh` | `kishi_ds4_diag.bin`: adds a flash boot log, register snapshot, raw ADC readout and a read-only memory dump. For debugging a firmware that won't start. Never ship it. |

Release audit (should print nothing, or `0`):

```bash
arm-none-eabi-nm kishi_ds4.elf | grep -ci diag
```

### 4.4 Run the firmware tests

Host unit tests (report building, config handling, live protocol, serial resolution). Needs gcc or clang:

```bash
bash tests/run_tests.sh
```

Expect `89 checks, 0 failures` and `60 checks, 0 failures` (counts grow as tests are added). If it can't find a compiler, set one:
`CC=clang bash tests/run_tests.sh`.

ARM emulator tests: these run the *real* ARM image in an emulator and compare it with the host build. They need `arm-none-eabi-nm` on `PATH` and
Python with `unicorn`:

```bash
pip install unicorn
python tests/emu_test.py
```

Every line should say `PASS`. (If your shell's `python` is the MSYS one and `pip` installs elsewhere, run the script with the Python that has `unicorn`
installed, for example `C:\Users\you\AppData\Local\Programs\Python\Python312\python.exe tests\emu_test.py`.)

### 4.5 Put the new firmware into the app

The app embeds `kishi_ds4.bin` at build time, so **rebuild the app after every firmware build**:

```powershell
cd KishiDS
dotnet publish -c Release -r win-x64 --self-contained false -p:PublishSingleFile=true -o publish
```

### 4.6 Changing the settings schema

The 256-byte config block shared by the firmware and the app is defined in exactly one place:
`firmware-research/tools/gen_config.py`. After editing it:

```bash
python firmware-research/tools/gen_config.py      # regenerates config_layout.h and KishiDS/Core/ConfigLayout.g.cs
cd firmware-research/ds4-firmware && ./build.sh   # firmware, with the new block and CRC
bash tests/run_tests.sh
cd ../../KishiDS && dotnet publish ...            # app (step 4.5)
```

`KishiDS.exe --selftest` includes a check that the C# defaults and CRC match the firmware-built block, so a forgotten step shows up there.

## 5. Flash a controller

### 5.1 One-time USB driver

The Kishi's bootloader (`Bootloader_GV`, USB ID `27F8:0BC0`) must be bound to the **WinUSB** driver, or Windows won't let the app talk to it.
With [Zadig](https://zadig.akeo.ie/):

1. Put the Kishi in update mode (below) so Windows sees `Bootloader_GV`.
2. Start Zadig, choose *Options > List All Devices*, select **Bootloader_GV**, choose **WinUSB** as the target driver, and click *Install Driver*.

If the app later reports that Windows "won't let the app open the bootloader (driver)", this step is what's missing. The normal controller
(`27F8:0BBF` or the DualShock-style `054C:05C4`) needs no driver.

### 5.2 Update mode

Unplug the Kishi. Hold **Y + B + Right Function**. Plug it in while holding. Windows should now show a `Bootloader_GV` device. This works whatever firmware is installed, so a bad application flash can always be recovered from here.

### 5.3 Flash with the app (recommended)

Open `KishiDS.exe`, go to **Firmware**, and click **Apply to controller**. The app tells you when to enter update mode, writes the firmware, waits for the
controller to come back, and verifies the flash by comparing the config checksum the controller reports.

### 5.4 Flash from the command line

```powershell
firmware-research\tools\dfu-util\dfu-util-static.exe -d 27f8:0bc0 -a 0 -D firmware-research\ds4-firmware\kishi_ds4.bin
```

Don't be alarmed by a final `LIBUSB_ERROR_PIPE`: the controller resets before dfu-util can read the last status. Success is `Download done.` with no
`dfuERROR` after it. A rejected image shows `status(7)` ("verification failed"), and the previous firmware keeps running.

After flashing, **always do a cold unplug and replug** (no buttons held) and confirm the controller appears. A warm check right after flashing can hide
startup bugs.

### 5.5 Get back to Razer's firmware

Razer's firmware is not in this repository. Get it once: the app can read it from Razer's own Android app (Razer Kishi 1.0.34 or 1.0.66 `.apk`) or
from a raw 28,108-byte `.bin`, checks it by SHA-256 (`AA915363C858E5A06FB1AD15A82D059D50FD22A625D0663A3CE99FCAB24F69BE`), and then **Restore original
firmware** on the Firmware page puts it back. By hand: enter update mode and run the dfu-util command above with that file.

## 6. Optional tools

Install the Python extras with `pip install -r requirements.txt` (from the repository root) for these:

| Script | Purpose |
|---|---|
| `firmware-research/tools/diag_read.py`, `diag_log.py`, `diag_mem.py` | Read a DIAG build over USB: live inputs, the boot log, a memory dump |
| `firmware-research/tools/capture_kishi_reports.py` | Capture HID reports from a controller |
| `KishiDS/tools/svg2xaml.py` | Regenerate `KishiDS/Theme/KishiArt.xaml` from `SVG/` |
| `KishiDS/tools/make_logo.py` | Cut the logo out of its backdrop and write `Assets/logo.png` and `kishi.ico` |

The research scripts that analyse Razer's images (`disasm_stock.py`, `make_stock_*_test.py`, `analyze_image_integrity.py`) expect Razer's firmware at
`firmware-research/official-images/` or `firmware-research/device-backups/`, which are deliberately not in the repository.

## 7. Developer flags and measuring

`KishiDS.exe` has headless and diagnostic modes. The ones you will use most:

| Command | What it does |
|---|---|
| `--status <log>` | Reports device state, serial number and telemetry |
| `--live <log> [--save] [--reboot]` | Hardware test of live editing against a connected controller |
| `--screenshot <Page> <png> [w h]` | Render a page off-screen (add `--demo` to avoid needing a controller) |
| `--perf <log> [Page]` | Measure UI responsiveness and device read rates for 6 seconds (add `--demo --stress` for random input) |

The full list is in [`ENGINEERING_NOTES.md`](ENGINEERING_NOTES.md) (section "Developer / automation flags").

## 8. Cutting a release

1. Rebuild the firmware if it changed (section 4), then publish the app (section 3).
2. Run the self-test (section 3) and the firmware tests (section 4.4).
3. Compute the checksum:

   ```powershell
   Get-FileHash KishiDS\publish\KishiDS.exe -Algorithm SHA256
   ```

4. Create a GitHub Release and attach `KishiDS.exe` and a `SHA256SUMS.txt` containing that hash. The `.exe` is not committed to the repository.
## 9. Troubleshooting

| Symptom | Cause and fix |
|---|---|
| `Cannot create temporary file in C:\WINDOWS\` while building libopencm3 | Add `CFLAGS=-pipe` to the `make` command (section 4.2). |
| `arm-none-eabi-gcc: No such file or directory` / `command not found` | devkitARM is not on `PATH`: use the devkitPro MSYS2 shell, or in Git Bash export `DEVKITARM` and add `$DEVKITARM/bin` to `PATH` (section 4.1). |
| `python: command not found` in `build.sh` | Install Python and make sure it is on `PATH` in the shell you use. |
| `build.sh` fails with missing `libopencm3_stm32f0.a` | Build libopencm3 first (section 4.2); check `git submodule update --init` ran. |
| `emu_test.py`: `No module named unicorn` | `pip install unicorn` for the same Python that runs the script. |
| `tests/run_tests.sh`: no compiler found | Install gcc or clang, or set `CC`. |
| App build: `error NETSDK1100` or WPF not found | You need the Windows .NET 9 **SDK**, on Windows. |
| App starts but says it needs a runtime | Install the .NET 9 **Desktop** Runtime. |
| Flash: "Windows won't let the app open the bootloader" | Install the WinUSB driver for `Bootloader_GV` with Zadig (section 5.1). |
| Flash: `status(7)` / "verification failed" | The image was rejected before it was written. Check the build used the vendor link base `0x08003000` (`./build.sh` does). |
| Controller not listed after flashing | Unplug and replug it with no buttons held. If it still isn't there, enter update mode (section 5.2) and reflash or restore. |
