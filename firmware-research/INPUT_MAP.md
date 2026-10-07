# Kishi 0290 v2.70: live input-report map

Captured from the physical controller on 2026-10-05 using read-only HID input reads. Reports are 11 bytes long and only arrive when state changes.

## Confirmed layout

| Report byte (one-based) | Meaning | Observed values |
|---:|---|---|
| 1 | Left-stick horizontal axis | Signed 8-bit; centre `00`, left movement enters the negative (`81`–`FF`) range |
| 2 | Left-stick vertical axis | Signed 8-bit; centre `00`, up movement enters the negative (`81`–`FF`) range |
| 3 | Right-stick horizontal axis | Signed 8-bit; centre `00`, left movement enters the negative (`81`–`FF`) range |
| 4 | Right-stick vertical axis | Signed 8-bit; centre `00`, up movement enters the negative (`81`–`FF`) range |
| 5 | D-pad hat | Neutral `80`; Up `00`, Right `20`, Down `40`, Left `60` |
| 6 | Auxiliary/non-gamepad field | No change in the captures so far |
| 7 | Primary buttons | A `01`, B `02`, X `08`, Y `10`, L1 `40`, R1 `80` |
| 8 | Secondary buttons | L2 digital threshold `01`, R2 digital threshold `02`, left Function `04`, right Function `08`, Home `10`, L3 `20`, R3 `40` |
| 9 | Auxiliary/non-gamepad field | No change in the captures so far |
| 10 | R2 analogue trigger | `00` at rest through `FF` fully pressed |
| 11 | L2 analogue trigger | `00` at rest through `FF` fully pressed |

## Still to capture

- Any additional special button not yet captured
- D-pad diagonals, if a target protocol needs them as separate positions

## Reusable capture helper

`tools/capture_kishi_reports.py` opens the `27F8:0BBF` HID interface in non-blocking read mode, records input reports only, and sends no output or feature report to the controller.
