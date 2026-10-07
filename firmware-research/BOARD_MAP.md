# Kishi 0290 board map (recovered from stock v2.70, verified on hardware 2026-10-06)

Image offsets are into `official-images/assets_legacy_02.70.bin` (link base `0x08003000`).
Use `tools/disasm_stock.py START END` to read any range.

## Clock / USB
- 12 MHz HSE crystal (HAL `HSE_VALUE`=12 MHz).  Custom firmware: HSE -> PLL x4 = 48 MHz, `RCC_CFGR3.USBSW`=PLL.
- USB FS device on PA11/PA12 (AF0).  Stock does not use CRS.

## Buttons (all active-low, internal pull-ups).  Stock scanner at 0x544C.
| Control | Pin | Control | Pin |
|---|---|---|---|
| A | PC2 | L1 | PC5 |
| B | PC3 | R1 | PA0 |
| X | PC0 | L3 | PC4 |
| Y | PC1 | R3 | PC13 |
| D-pad Up | PB2 | Right Function | PA8 |
| D-pad Down | PB1 | Left Function | PA4 |
| D-pad Left | PB11 | Home | PA7 |
| D-pad Right | PB10 | | |

Verified on hardware: every control above registers; D-pad Up/Down/Left/Right -> hat 0/4/6/2.

## Analog (12-bit ADC, polled; stock uses DMA).  Stock scaling at 0x492C / 0x4952 / 0x49E2.
| Channel | Pin | Function | Rest (raw) |
|---|---|---|---|
| ADC1 | PA1 | right stick X | ~2000 |
| ADC2 | PA2 | right stick Y | ~2073 |
| ADC3 | PA3 | R2 trigger | ~1960 (falls when pressed) |
| ADC5 | PA5 | left stick Y | ~2040 |
| ADC6 | PA6 | left stick X | ~2130 |
| ADC8 | PB0 | L2 trigger | ~2145 (falls when pressed) |

## Calibration struct: flash page 0x0800F800 (0x59 bytes), valid if u32 at +0x55 + 0xD22F8CD7 == 0
Little-endian u16 fields: stick max/min `0x00..0x0E` (order: right X, right Y, left X, left Y), deadzone `0x18` (110),
trigger hi `0x10`/`0x14` (+ signed offset `0x1E`/`0x22`), trigger lo `0x12`/`0x16` (+ offset `0x1C`/`0x20`),
stick centers `0x34`(left X) `0x36`(left Y) `0x38`(right X) `0x3A`(right Y), float 0.9 at `0x3C` (full-scale at 90% travel),
digital-trigger threshold byte `0x1A` (=10).  Stock rewrites the page with defaults if the magic is invalid.

Stick: raw>center -> `127*(min(raw,max)-(center+dead)) / (0.9*(max-(center+dead)))`; below center mirrored with `min`; clamp +-127.
Trigger: `~(255*(clamp(raw,lo,hi)-lo)/(hi-lo))`.  Kishi report sticks are signed with up = negative (left Y is negated).

## Other GPIO
- PB14 and PC9: push-pull outputs driven HIGH at boot (purpose unknown; replicated).
- PB4: TIM3_CH1 PWM in stock = the blue LED.  **Active-low** (verified on hardware: dark with PB4 held high, blinks when toggled, solid with PB4 held low).  Custom firmware holds PB4 low for a steady light.
- Unused pins are pulled down (PC6-8,11,12,14,15, PB3,5-9,15, PA9,10, PD2); PB12/13, PA15, PC10 floating.
- Flash page 0x0800F800 is the settings page: never overwrite it with firmware.
