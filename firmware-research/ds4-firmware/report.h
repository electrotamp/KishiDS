/*
 * Kishi controls -> DualShock 4 USB input report.  Pure logic (no hardware access), so it is
 * unit-tested on the host (tests/test_report.c).
 */
#ifndef REPORT_H
#define REPORT_H

#include <stdint.h>

#include "config.h"
#include "kishi_io.h"

/* Stick/trigger calibration, indexed in stock ADC raw[] order. */
struct kishi_cal {
	uint16_t smax[4], smin[4], scenter[4];   /* RX, RY, LX, LY */
	uint16_t t_lo[2], t_hi[2];               /* L2, R2: raw when pressed / at rest */
};

#define KISHI_REPORT_SIZE 64u

/* Calibration stored in the config block (used when calib_mode == 1, and as the fallback). */
void report_cal_from_config(struct kishi_cal *cal, const struct kishi_config *cfg);

/*
 * Calibration from the stock firmware's settings page (0x59 bytes at 0x0800F800), valid if the
 * u32 at +0x55 plus 0xD22F8CD7 is zero.  Returns 1 and fills *cal when valid, else 0.
 */
int report_cal_from_stock_page(struct kishi_cal *cal, const uint8_t *page);

/* Build the 64-byte report (ID 0x01 + 63 payload bytes). */
void report_build(const struct kishi_config *cfg, const struct kishi_cal *cal, uint16_t buttons,
		  const uint16_t adc[KISHI_ADC_COUNT], uint8_t counter, uint8_t out[KISHI_REPORT_SIZE]);

#endif
