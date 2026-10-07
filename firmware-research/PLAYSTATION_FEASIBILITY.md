# PlayStation-style USB emulation: feasibility study

## Bottom line

This is technically *possible in principle* as a firmware-only project, but it is a replacement-firmware project rather than a descriptor patch. The best target is an older DualShock 4-style wired profile, not DualSense and not Xbox.

A USB-only first-stage custom image has been built, but it failed at final manifest before it could run.  Later stock-derived one-byte tests proved that the bootloader permits modified firmware bytes, including a USB product-string change that booted successfully.  A practical firmware-only path is therefore blocked by an unresolved image-layout, protected-region, or runtime requirement—not a demonstrated whole-image signature gate.

## What is established about this Kishi

- The raw v2.70 image begins with a Cortex-M-style vector table: initial stack pointer `0x20004000` and reset-vector value `0x08009841`.  The vector entries are self-consistent when the image is placed at `0x08009000`: the reset handler resolves to image offset `0x0840` and the interrupt handlers resolve to code inside the same image.  Its 28,108 bytes end at `0x0800FDCC`, immediately below the 64 KiB boundary.  The DFU updater supplies no explicit target address, so this is strong static evidence of the application slot, not a direct observation of the bootloader's internal write routine.
- The firmware uses the STM32F0-family register layout: USB peripheral at `0x40005C00`, GPIO at `0x48000000`, and RCC at `0x40021000`. This identifies the MCU family, but not the exact part number.
- It currently runs USB full speed and exposes one interrupt IN and one interrupt OUT endpoint. The current 12-byte endpoint sizes are a firmware choice, not a demonstrated hardware ceiling.
- The STM32F0 USB device block supports up to eight endpoints and 1 KiB of dedicated USB packet memory on the relevant USB-capable variants. Full-speed interrupt packets can be up to 64 bytes. That is enough for a conventional wired controller report protocol.
- The bootloader is separate from the application and accepts raw DFU downloads. It refused firmware upload/readback, so there is no device-specific dump.

## What a DualShock-style application must do

1. **Preserve a recoverable application boundary.** The vendor DFU bootloader must remain untouched. The app must also leave the physical button combination able to enter that bootloader.
2. **Initialize the existing board.** Configure clocks, GPIO/button matrix scanning, analogue-stick ADC inputs, and any power/pass-through circuitry needed for normal operation.
3. **Enumerate as the selected target.** Supply the selected controller family's device, configuration, HID, report, and string descriptors. This is more than changing a name or vendor/product identifier.
4. **Produce correctly formatted state reports.** Translate the Kishi controls into the target controller's button, D-pad, sticks, triggers, and neutral values for controls the Kishi lacks (such as touchpad, motion sensors, speaker, and light bar).
5. **Handle host requests.** Implement the standard USB/HID requests and the target's observable feature/output-report behavior. Some output commands can initially be accepted and ignored; others may be required before iOS treats it as usable.
6. **Test against iOS.** Windows recognition is only an intermediate test. The decisive behavior is the sequence of USB requests the iPhone sends after enumeration.

## Information still needed

| Item | Why it matters | How to obtain it without opening the Kishi |
|---|---|---|
| Exact MCU part and flash capacity | Determines code/RAM limits and the safe application size | Further binary analysis may narrow it; a clear board photo would prove it, but is not required to start analysis. |
| DualShock wired USB trace | Defines the descriptors, feature reports, and host-request sequence to reproduce | Capture an actual controller on a test computer; an iPhone-specific trace is more valuable, but requires a USB-C protocol analyser or equivalent setup. |
| Kishi pin/ADC map | Lets new firmware read every control | Reverse-engineer the existing v2.70 image offline. |
| Recovery proof | Establishes that the known-good firmware can be restored | Completed 2026-10-05: the stock v2.70 image restored the Kishi after both rejected custom-image tests. |
| Stock image-format analysis | Identifies the structure the full replacement did not preserve | Compare the early vectors, reset layout, metadata, and flash-page assumptions of stock with the failed replacement. |

## Constraints and risk

- An iPhone Kishi firmware or Kishi V2 firmware must **not** be used as a donor image. They target different boards and/or connector/accessory hardware.
- Copying a PlayStation identifier alone is expected to fail because the host can request behavior that a generic HID Kishi does not implement.
- Full DualSense emulation is a poor first target because it adds more device-specific features. A DualShock 4-style wired profile is narrower and better matches the Kishi's controls.
- The bootloader's DFU download capability makes controlled stock recovery feasible, but upload/readback was rejected and full replacements failed at manifest.  Stock-derived one-byte mutations can boot, while an attempted instruction mutation produced malformed USB behavior.  Without opening the controller, recovery relies on the official v2.70 image and a bootloader that remains reachable.

## Recommended staged plan

1. Reverse-engineer the v2.70 image's reset, GPIO/ADC setup, input scan, USB descriptors, and USB callbacks.
2. Create a host-side model that converts Kishi state reports to the candidate PlayStation report layout; test that model before a device build exists.
3. Obtain and analyze a wired DualShock 4 USB trace, preferably including iPhone connection behavior.
4. Resolve the stock image layout/protected-region/runtime expectation that the full replacement failed to preserve; use only small, observable stock-derived tests.
5. Only then build a minimal replacement application: read controls, enumerate, emit neutral-compatible reports, and retain a DFU return route.
