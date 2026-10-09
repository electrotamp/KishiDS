/*
 * Kishi V2 Pro board layer: physical buttons and analog inputs.
 *
 * Pins come from Razer firmware 2.2 (../BOARD_MAP_V2PRO.md); which pin is which button was confirmed on a controller
 * with `tools/v2pro_probe.py buttons`.  The names live in one table (board_v2pro.c).
 */
#ifndef BOARD_H
#define BOARD_H

#include <stdint.h>

#include "kishi_io.h"   /* shared button enum and ADC order used by report.c (from ../ds4-firmware) */

/* Physical buttons, one bit each in the value board_read_buttons() returns. */
enum v2pro_button {
	VB_A, VB_B, VB_X, VB_Y,
	VB_UP, VB_DOWN, VB_LEFT, VB_RIGHT,
	VB_L1, VB_R1, VB_L3, VB_R3,
	VB_M1, VB_M2,
	VB_VIEW, VB_MENU, VB_HOME, VB_SHARE,
	VB_COUNT
};

/*
 * Recovery combo: View + Menu held while the controller is plugged in.  Call first thing at boot, before any other
 * set-up (it only needs the pins, on the reset clock).  Nonzero = both held for the whole sampling window.
 */
int board_boot_combo_held(void);

/* Pins, plus the ADC (calibrated, first scan started). */
void board_init(void);

/* Bit n set = button n (enum v2pro_button) pressed. */
uint32_t board_read_buttons(void);

/* Shared button bits past the V1's: Share through button_map[15], M1/M2 through button_map2[0..1] (report.c). */
#define KB_V2PRO_SHARE KB_COUNT
#define KB_V2PRO_M1    16u
#define KB_V2PRO_M2    17u
#define KB_NONE        0xFFu

/* Physical buttons -> the shared Kishi button bits report.c understands. */
uint32_t board_to_kishi_buttons(uint32_t v2pro_buttons);

/* The chip's 128-bit factory UUID (NMPA + 0x70 = 0x9FC70 on this 640 KB LPC55).  0, or -1 if it cannot be read. */
int board_uid(uint8_t out[16]);

/* Stick/trigger raw 12-bit values in the shared order (see kishi_io.h); never blocks.  Axis assignment: board_v2pro.c. */
void board_read_adc(uint16_t out[KISHI_ADC_COUNT]);

#endif
