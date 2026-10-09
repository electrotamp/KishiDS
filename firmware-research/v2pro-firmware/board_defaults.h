/*
 * Kishi V2 Pro defaults that differ from the V1's (shared config_layout.h), added to the image's config block by
 * ../ds4-firmware/config.c (build.sh: KCFG_BOARD_OVERRIDES_HEADER).  The KishiDS calibration wizard replaces them,
 * (and its SAVE persists), so these are what a controller starts with before that or after a new image.
 *
 * From one controller (2026-10-07, ../device-backups/phase5_raw.txt, values as board_read_adc() delivers them, i.e.
 * with both Y axes mirrored).  Order RX, RY, LX, LY.  Each stick axis was measured at rest and at one end (right on
 * X, up on Y); the other end is mirrored around the centre, and the 90 % outer setting leaves margin for both.
 *   rest      RX 2237, RY 2083, LX 1947, LY 2094
 *   measured  RX 3489 (max), RY 761 (min), LX 3265 (max), LY 3354 (max)
 * Triggers (L2, R2) rest at ~1640 / ~1735 and read ~50 / ~136 pressed; rest and pressed are taken 60 / 50 counts
 * inside those so a trigger at rest reads 0 and a full press reaches 255.
 */
#ifndef BOARD_DEFAULTS_H
#define BOARD_DEFAULTS_H

/* LED: lit while the host has the controller configured (mode 3), in the lightbar colour the host sets. */
/*
 * Buttons: as the V1's, except View -> touchpad click and Share (slot 15) -> DS4 Share, so on iOS the screenshot
 * button takes screenshots (iOS's default for DS4 Share), Nexus is PS (Home), and View is still a button games see.
 * M1 / M2 (button_map2): a touchpad click with a finger on the left / right half.
 */
#define KCFG_BOARD_OVERRIDES \
	, .button_map = KCFG_V2PRO_BUTTON_MAP \
	, .button_map2 = KCFG_V2PRO_BUTTON_MAP2 \
	, .led_mode = 3 \
	, .cal_smax = {3489, 3405, 3265, 3354} \
	, .cal_smin = {985, 761, 629, 834} \
	, .cal_scenter = {2237, 2083, 1947, 2094} \
	, .cal_t_lo = {100, 186} \
	, .cal_t_hi = {1580, 1675}

#endif
