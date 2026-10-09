/*
 * Rumble on the Kishi V2 Pro (see haptics_v2pro.h).  From Razer's firmware 2.2 (static, with .data recovered by
 * emulating its start-up):
 *   - drv_write(side, reg, value) at 0x16740 / drv_read at 0x16710: side 0 on FLEXCOMM1, side 1 on FLEXCOMM4,
 *     device 0x5A (8-bit 0xB4); it reads registers 0x00..0x22 of both at start-up, a DRV2605 register map.
 *   - pins (BOARD_InitPins, emulated): P0_13 / P0_14 IOCON 0x101 (FLEXCOMM1 I2C), P1_20 / P1_21 0x105 (FLEXCOMM4);
 *     P1_18 is driven high 200 ms before the I2C devices are touched (board_init drives it, haptics wait 250 ms).
 *   - init (0x158f8): MODE = 0x40 (standby), then the table at 0x200100ac (below: LRA, closed loop, Razer's stored
 *     calibration), then MODE = 0x05 (RTP) and RTP = 0.
 *   - level (0x1623a): RTP = percent x 127 / 100 per side, i.e. 0..127.
 */
#include "haptics_v2pro.h"

#include "config.h"
#include "i2c_lpc55.h"
#include "lpc55.h"

#define DRV_ADDR      0x5Au
#define DRV_STATUS    0x00u
#define DRV_MODE      0x01u
#define DRV_RTP       0x02u
#define MODE_STANDBY  0x40u
#define MODE_RTP      0x05u
#define INIT_AFTER_MS 250u
/*
 * A DS4 rumble level of 255 drives the actuators at kcfg.rumble_level % of RTP full scale (127; KishiDS app), or at
 * this default when the field is 0.  Chosen on the controller (2026-10-09) from a 20/40/60/80/100 % step test: 100 %
 * "feels overboard", 80 % is right.
 */
#define DEFAULT_PERCENT 80u

static const uint8_t side_flexcomm[2] = {1, 4};

/* Razer's register table (reg, value), written in this order to both drivers. */
static const uint8_t drv_config[][2] = {
	{0x01, 0x00},   /* MODE: active, internal trigger */
	{0x02, 0x00},   /* RTP: 0 */
	{0x17, 0x89},   /* OD_CLAMP */
	{0x1D, 0x80},   /* CONTROL3 */
	{0x16, 0x4C},   /* RATED_VOLTAGE */
	{0x18, 0x0C},   /* A_CAL_COMP (stored calibration) */
	{0x19, 0x6C},   /* A_CAL_BEMF (stored calibration) */
	{0x1A, 0xA4},   /* FEEDBACK: LRA, closed loop */
	{0x1B, 0x9A},   /* CONTROL1 */
	{0x1C, 0xF5},   /* CONTROL2 */
};

uint8_t haptics_status[2] = {0xFF, 0xFF};
static uint8_t ready[2];
static int16_t last_rtp[2] = {-1, -1};
static int initialised;

static void init_side(unsigned side)
{
	unsigned fc = side_flexcomm[side], i;
	int ok;

	i2c_init(fc);
	if (i2c_read_reg(fc, DRV_ADDR, DRV_STATUS, &haptics_status[side])) {
		haptics_status[side] = 0xFF;
		return;
	}
	ok = i2c_write_reg(fc, DRV_ADDR, DRV_MODE, MODE_STANDBY) == 0;
	for (i = 0; ok && i < sizeof(drv_config) / sizeof(drv_config[0]); i++) {
		ok = i2c_write_reg(fc, DRV_ADDR, drv_config[i][0], drv_config[i][1]) == 0;
	}
	ok = ok && i2c_write_reg(fc, DRV_ADDR, DRV_MODE, MODE_RTP) == 0;
	ok = ok && i2c_write_reg(fc, DRV_ADDR, DRV_RTP, 0) == 0;
	ready[side] = (uint8_t)ok;
	last_rtp[side] = 0;
}

static void init_pins(void)
{
	IOCON_PIO(0, 13) = 0x101u;   /* FLEXCOMM1 I2C, as Razer's BOARD_InitPins */
	IOCON_PIO(0, 14) = 0x101u;
	IOCON_PIO(1, 20) = 0x105u;   /* FLEXCOMM4 I2C */
	IOCON_PIO(1, 21) = 0x105u;
}

void haptics_update(uint32_t ms, uint8_t strong, uint8_t weak)
{
	const uint8_t level[2] = {strong, weak};   /* side 0 (FLEXCOMM1) is the left actuator: checked on the controller */
	unsigned side, percent = kcfg.rumble_level ? kcfg.rumble_level : DEFAULT_PERCENT;

	if (!initialised) {
		if (ms < INIT_AFTER_MS) {
			return;
		}
		initialised = 1;
		init_pins();
		init_side(0);
		init_side(1);
	}
	for (side = 0; side < 2; side++) {
		int16_t rtp = (int16_t)(level[side] * 127u * percent / (255u * 100u));

		if (ready[side] && rtp != last_rtp[side]) {
			if (i2c_write_reg(side_flexcomm[side], DRV_ADDR, DRV_RTP, (uint8_t)rtp) == 0) {
				last_rtp[side] = rtp;
			}
		}
	}
}
