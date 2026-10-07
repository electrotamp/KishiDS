# Razer Kishi V1 (RZ06-0290) — bring-up history

> Historical log of the reverse-engineering and first custom-firmware attempts. Some early claims here (for example an application base
> of `0x08009000`) were wrong and are corrected inline; the current, accurate reference is [`docs/ENGINEERING_NOTES.md`](../docs/ENGINEERING_NOTES.md).

## Physical state when this log was written (historical)

The controller was recovered successfully and is in normal, healthy stock mode:

- USB runtime identity: `27F8:0BBF` (`Razer Kishi`), serial `SAMPLE000000001`.
- Windows reports a healthy HID game-controller child device and USB product name `Kishi`.
- The verified restoration image is `device-backups/kishi-stock-v2.70-recovery.bin`.
- Its SHA-256 is `AA915363C858E5A06FB1AD15A82D059D50FD22A625D0663A3CE99FCAB24F69BE`.

Do not reflash unrelated Kishi iPhone/V2 images.

## CORRECTION (2026-10-06): application base is 0x08003000, NOT 0x08009000

Every earlier note that says "linked at / flash base `0x08009000`" is **wrong**.  Evidence (all offline, `official-images/assets_legacy_02.70.bin`):

- Pointer-like words that land inside the image: **175/178 at base `0x08003000`**, vs 76/178 at `0x08009000`.
- Default IRQ handler (26 vectors share `0x08009891`) is `b .` (`FE E7`) at image offset `0x6890` → base `0x08009890 - 0x6890 = 0x08003000`.
- Reset handler is at offset **`0x6840`** (vector `0x08009841`): classic GCC crt0 — loads SP `0x20004000`, copies `.data` from flash `0x08009AE8` to RAM `0x20000100..0x200003E4`, zeroes `.bss` to `0x2000100C`, then `bl SystemInit`, `bl __libc_init_array`, `bl main`.  (Offset `0x0840`, as the old notes say, is mid soft-float library code.)
- So the app occupies **`0x08003000`-`0x08009DCC`**; the vendor bootloader is the 12 KB below it (`0x08000000`-`0x08002FFF`).
- App also references flash page `0x0800F800` (likely a settings page) — leave it alone.
- RAM `0x20000000`-`0x200000FF` is deliberately unused by stock (`.data` starts `0x20000100`) — fits a bootloader-copied/remapped vector table (stock app has no VTOR/SYSCFG remap code; Cortex-M0 has no VTOR).  Keep it free in custom images.
- Stock app contains no CRC peripheral use, no CRC tables, no self-check of its own flash; no stored checksum for any common CRC16/CRC32/sum layout (brute-forced).  The official updater sends only raw 1 KiB DNLOAD blocks — no length/CRC side-channel.

**Consequence:** `kishi_ds4_stage1*.bin` were linked for `0x08009000`.  Their reset vector `0x0800A0CD` lies *outside* the stock-sized app region (`0x08003000`+`0x6DCC` = `0x08009DCC`) and well past the end of the raw 7,416-byte image.  Leading hypothesis for DFU status 7 at manifest: the bootloader validates the vector table (SP and/or reset/handler pointers must fall inside the app region/image) — stock-derived edits keep the vector table intact, which is why they pass.  **Unproven** until a re-based image is tried.

New re-based build (not yet flashed): `ds4-firmware/kishi_ds4_stage1_app3000.bin` (7,416 B, ROM `0x08003000`, RAM `0x20000100`, reset `0x080040CD`, all vectors inside the image), linker script `ds4-firmware/stm32f072_kishi_app3000.ld`.

Experiment B re-analysis: patch at `0x16D9` changed halfword `0x16D8` `BF00`→`4600` (a true NOP→`MOV r0,r0`), in padding before the function at `0x16DC`, never executed.  The app still failed to enumerate, so something *does* care about code-region bytes (bootloader boot-time check or similar) — **still unexplained**; do not assume code edits are safe.

### RESULT (2026-10-06): first custom image BOOTED

- Re-based stage1 v1 (HSI48, no CRS; SHA-256 `722D0E35…9826`, 7,416 B): **manifest accepted (status 0)** — so the status-7 rejections were the wrong base / vector table, confirming the hypothesis — but it did not enumerate (`Device Descriptor Request Failed`).  Recovered to stock.
- Stock clock facts: HAL `HSE_VALUE` = **12 MHz** (board has a 12 MHz crystal), `HSI48` = 48 MHz constant; stock does not use CRS.
- Re-based stage1 v2 (`ds4-firmware/kishi_ds4_stage1_app3000.bin`, SHA-256 `938EA716…E728`, 7,692 B): HSE 12 MHz → PLL ×4 = 48 MHz, USBSW=PLL (fallback HSI48+CRS).  **Manifest status 0, and the controller enumerated as `054C:05C4` (DualShock 4), serial `KISHI-DS4-TEST`, HID game controller OK.**
- Therefore: no signature/hash gate; bootloader validates the vector table against the app region (`0x08003000`); a custom app linked at `0x08003000` with RAM from `0x20000100` and correct HSE/PLL clocks boots and runs USB.  Experiment B (dead-padding edit failing) is still unexplained but is no longer blocking.
- Controller is currently running stage1 v2 (neutral DS4 reports only, no input scanning yet).  Restore stock with the recovery command below if needed.

## Hardware / bootloader facts

- Normal mode: `27F8:0BBF`, one USB HID interface.
- DFU mode: enter by holding **Y + B + Right Function** while reconnecting USB.
- DFU identity: `27F8:0BC0`, `Bootloader_GV`, `GV Bootloader V1.0.2`, serial `12345678`, alternate setting `0`.
- DFU transfer size: 1,024 bytes.  The Android updater sends raw blocks followed by zero-length `DNLOAD`.
- `dfu-util` upload/read-back is refused with `LIBUSB_ERROR_PIPE`; the bootloader binary cannot currently be dumped via USB.
- `dfu-util` emits an “Invalid DFU suffix” warning for the stock raw image, but stock and modified images still transfer and boot.  That warning is not the current blocker.
- The bootloader can always be reached with the physical button combination after a bad application image.  Stock v2.70 recovery has been proven repeatedly.

## The critical correction: no whole-image signature/hash gate

An earlier conclusion that the bootloader required a full-image signature/hash was **wrong**.  Two controlled, same-length stock mutations show that the bootloader will transfer and leave DFU with modified firmware bytes.

### Experiment A: descriptor/data-byte mutation — successful boot

Base: exact stock v2.70 image.

| Property | Value |
|---|---|
| Experiment file | `experiments/stock-v2.70-product-string-test.bin` |
| SHA-256 | `AF5A46CE3AA660AF39144B0B5C4DFDCCF810FF527F9F732AA3077C89F33F95A6` |
| Changed offset | `0x6AA8` |
| Only mutation | ASCII `i` (`0x69`) -> `x` (`0x78`) in the NUL-terminated USB product string `Kishi` |
| File length | unchanged: 28,108 bytes |
| DFU result | full transfer; `dfuMANIFEST-SYNC`, status 0; device left DFU |
| Runtime proof | Windows `DEVPKEY_Device_BusReportedDeviceDesc` changed to **`Kishx`** |

This rules out an exact whole-image hash/signature or whole-image CRC enforced before boot.  It does **not** rule out protected regions, a header check, or application-side self-checks.

### Experiment B: one executable-byte mutation — application fails to enumerate

Base: exact stock v2.70 image.

| Property | Value |
|---|---|
| Experiment file | `experiments/stock-v2.70-nop-code-test.bin` |
| SHA-256 | `6EAC7B320C5B1BCB0130F6BBB34BE161D27EDD5742FC46E7B6E2676A56CB5F32` |
| Changed offset | `0x16D9` |
| Mutation | Thumb halfword `00 BF` (decoded linearly as `NOP`) -> `00 46` (`MOV r0, r0`) |
| File length | unchanged: 28,108 bytes |
| DFU result | full transfer; `dfuMANIFEST-SYNC`, status 0; no `dfuERROR` reported after manifest |
| Runtime result | Windows displayed an unrecognized-USB-device notification; no normal Kishi or DFU device remained until the physical button combo was used |

**Do not treat the `NOP` label as proof that this was semantically inert.** It may have been embedded data, its surrounding control flow may be non-obvious, or the application may self-check that region.  The test does establish that the host-side DFU transfer itself was not rejected at final manifest like the full replacement image.

The controller was recovered from this state with the exact stock image and verified healthy afterward.

## Why the first custom replacement failed

Two raw replacement-stage images were tried before the stock-mutation tests:

| Image | Length | Manifest result |
|---|---:|---|
| `ds4-firmware/kishi_ds4_stage1.bin` | 7,416 bytes | `dfuERROR`, status 7: “Programmed memory failed verification” |
| `ds4-firmware/kishi_ds4_stage1_slot.bin` | 28,108 bytes | same status 7 after padding with `0xFF` to stock length |

Neither replacement ever booted.  Image length is therefore not sufficient.  The new experiments strongly suggest a **layout/format/required-region/runtime issue**, not an all-byte cryptographic signature.  Do not claim the exact cause yet.

Useful static facts about stock v2.70:

- It is linked at application flash base `0x08009000`.
- Initial stack pointer: `0x20004000`.
- Stock reset vector: `0x08009841` (code begins at raw offset `0x0840`).
- Application image length: 28,108 (`0x6DCC`) bytes, exclusive mapped end `0x0800FDCC`.
- MCU behavior and vector layout strongly indicate an STM32F072-class Cortex-M0, but exact package is not proven.
- The stage replacement linked at the correct nominal base but has a different reset-handler/layout.  Compare all early sections and any stock metadata before assuming a generic STM32 binary is acceptable.

## Outcome

The open questions in this log were resolved on 2026-10-06 (see the correction above): the replacement images had been linked at the wrong base address, a custom image linked at `0x08003000` boots, and the work went on to a complete DualShock 4 firmware. For the current state and what is still unknown, see [`docs/ENGINEERING_NOTES.md`](../docs/ENGINEERING_NOTES.md). The to-do list that used to be here no longer applies and was removed.

## Local artifacts and tools

- `README.md` — project overview.
- `FIRMWARE_MAP.md` — static addresses, input path, USB anchors, DFU observations.
- `INPUT_MAP.md` — confirmed 11-byte stock HID input format.
- `DS4_TARGET.md` — proposed DualShock 4 report target; do not prioritize yet.
- `ds4-firmware/` — the firmware (when this log was written it was only a first USB-only build that did not flash; it is now complete).
- `tools/dfu-util/dfu-util-static.exe` — used for recovery and experiments.
- `tools/make_stock_string_test.py` and `tools/make_stock_nop_test.py` — guarded local builders for the two exact experiments.
- `tools/analyze_image_integrity.py` — offline common CRC-layout scan; it found no simple stored CRC-32 field.

## Proven recovery command shape

Only after Windows shows `Bootloader_GV` (`27F8:0BC0`):

```powershell
firmware-research\tools\dfu-util\dfu-util-static.exe -d 27f8:0bc0 -a 0 -D firmware-research\device-backups\kishi-stock-v2.70-recovery.bin
```

Then verify Windows returns `USB\VID_27F8&PID_0BBF` with bus-reported product `Kishi`.
