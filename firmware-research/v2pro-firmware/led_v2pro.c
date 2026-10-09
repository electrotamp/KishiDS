/*
 * Kishi V2 Pro RGB LED on SCTimer outputs 0-2 (P0_2, P0_3, P1_25; IOCON functions 3, 3, 2 as in Razer's
 * BOARD_InitPins).  Razer's firmware 2.2, static: set-up at 0xe77a, colour at 0xc9e6, which writes
 * MATCHREL1..3 = channel x brightness for outputs 0..2; a colour it uses is (255, 144, 19) at brightness 76, stored
 * as bytes 6..8 of a state block (0x16b0c).  Confirmed on the controller (2026-10-08, lightbar colours sent from a
 * PC): outputs 0..2 are red, green, blue, and the LED lights while its output is high.
 *
 * PWM as Razer's: one 16-bit counter limited by match 0 (period 0xFFFF), event 0 at the limit sets all three outputs,
 * event n (match n) clears output n-1, so match n / 65536 is the time the output is high.  Razer's counter runs from
 * 96 MHz / 10; ours is 48 MHz / 5, the same ~146 Hz.  RES makes a coincidence of the two events clear the output, so
 * a match of 0 really is dark; matches are kept below the limit for the same reason.
 */
#include "led_v2pro.h"

#include <string.h>

#include "clock.h"
#include "config.h"
#include "diag.h"
#include "lpc55.h"

#define SCT_BASE      0x40085000u
#define SCT(off)      LPC55_REG32(SCT_BASE + (off))
#define SCT_CONFIG    SCT(0x000)
#define SCT_CTRL      SCT(0x004)
#define SCT_OUTPUT    SCT(0x050)
#define SCT_RES       SCT(0x058)
#define SCT_MATCH(n)  SCT(0x100 + 4u * (n))
#define SCT_MATCHREL(n) SCT(0x200 + 4u * (n))
#define SCT_EV_STATE(n) SCT(0x300 + 8u * (n))
#define SCT_EV_CTRL(n)  SCT(0x304 + 8u * (n))
#define SCT_OUT_SET(n)  SCT(0x500 + 8u * (n))
#define SCT_OUT_CLR(n)  SCT(0x504 + 8u * (n))

#define AHBCLK1_SCT        (1u << 2)
#define CONFIG_AUTOLIMIT_L (1u << 17)
#define CTRL_HALT_L        (1u << 2)
#define CTRL_HALT_H        (1u << 18)   /* the unused high counter: halted for good */
#define CTRL_CLRCTR_L      (1u << 3)
#define CTRL_PRE_L(n)      ((uint32_t)(n) << 5)
#define EV_CTRL_MATCH(n)   (0x1000u | (n))     /* COMBMODE = match only, MATCHSEL = n */

/* The LED lights while its output is high (checked on hardware); 1 would invert every channel. */
#define LED_ACTIVE_LOW 0

/* The KishiDS colour (kcfg.led_rgb) when it is 0, 0, 0, and so the colour before any is set: blue, as a PS4's player 1. */
#define DEFAULT_R 0
#define DEFAULT_G 0
#define DEFAULT_B 255

static const struct { uint8_t port, pin, func; } led_pins[3] = {{0, 2, 3}, {0, 3, 3}, {1, 25, 2}};   /* R, G, B */
static uint8_t colour[3] = {DEFAULT_R, DEFAULT_G, DEFAULT_B};

void led_init(void)
{
	unsigned i;

	/* As Razer's 0xe77a: clock on, then a reset that is waited for (0xe208). */
	/*
	 * The SCTimer's own function clock (SCTCLKSEL resets to "none"), selected as NXP's drivers do.  Razer's application
	 * never selects it, and selecting it did not change the start-up fault (that was HALT_H, below), so this is not
	 * known to be required.  Main clock (96 MHz), divider 1: source first, then the divider, as for USB0.
	 */
	SYSCON_SCTCLKSEL = 0u;
	SYSCON_SCTCLKDIV = 0u;
	WAIT_UNTIL(!(SYSCON_SCTCLKDIV & CLKDIV_REQFLAG), DW_SCTCLKDIV);
	DIAG_STEP(1);
	SYSCON_AHBCLKCTRLSET(1) = AHBCLK1_SCT;
	DIAG_STEP(2);
	clock_peripheral_reset(1, AHBCLK1_SCT);
	DIAG_STEP(3);

	SCT_CONFIG = CONFIG_AUTOLIMIT_L;
	DIAG_STEP(4);
	/*
	 * Both halves stay halted (HALT_H too, the reset value; Razer only ORs bits into CTRL).  A 32-bit MATCH write
	 * touches both halves, and writing the match registers of a running counter is a bus error: on hardware, with
	 * HALT_H cleared here, the MATCH0 write below faulted (BFAR 0x40085100, by the timing channel).
	 */
	SCT_CTRL = CTRL_HALT_L | CTRL_HALT_H | CTRL_CLRCTR_L | CTRL_PRE_L(4);
	SCT_MATCH(0) = 0xFFFFu;
	SCT_MATCHREL(0) = 0xFFFFu;
	DIAG_STEP(5);
	for (i = 0; i < 4; i++) {
		SCT_EV_STATE(i) = 1u;                 /* every event in state 0 */
		SCT_EV_CTRL(i) = EV_CTRL_MATCH(i);
	}
	for (i = 0; i < 3; i++) {
		SCT_MATCH(i + 1) = 0;
		SCT_MATCHREL(i + 1) = 0;
		SCT_OUT_SET(i) = 1u << 0;             /* event 0: period start */
		SCT_OUT_CLR(i) = 1u << (i + 1);       /* event i + 1: end of the high time */
	}
	DIAG_STEP(6);
	SCT_RES = 0x2Au;                          /* conflict on outputs 0-2: clear */
	DIAG_STEP(7);
	SCT_OUTPUT = LED_ACTIVE_LOW ? 7u : 0u;    /* dark until the first update */
	DIAG_STEP(8);
	for (i = 0; i < 3; i++) {
		IOCON_PIO(led_pins[i].port, led_pins[i].pin) = led_pins[i].func | IOCON_DIGIMODE;
	}
	DIAG_STEP(9);
	SCT_CTRL &= ~CTRL_HALT_L;
	DIAG_STEP(10);
}

void led_set_lightbar(uint8_t r, uint8_t g, uint8_t b)
{
	colour[0] = r;
	colour[1] = g;
	colour[2] = b;
}

/* 0..255 brightness -> perceptual level (gamma 2), as the V1. */
static unsigned gamma_level(unsigned level)
{
	return level * level / 255u;
}

/* duty 0..255 = lit fraction.  MATCHREL is a reload register (taken at the next period): rewriting it is harmless. */
static void set_channel(unsigned ch, unsigned duty)
{
	uint32_t high = LED_ACTIVE_LOW ? 255u - duty : duty;

	SCT_MATCHREL(ch + 1) = high * 257u > 0xFFFEu ? 0xFFFEu : high * 257u;
}

void led_update(uint32_t ms, int active)
{
	static uint8_t applied[3];
	static int have_applied;
	uint8_t own[3];
	const uint8_t *shown;
	unsigned level = 0, i;

	/* The KishiDS colour: shown from start-up and whenever it changes; a host's lightbar colour replaces it until then,
	 * unless led_fixed keeps it. */
	if (kcfg.led_rgb[0] | kcfg.led_rgb[1] | kcfg.led_rgb[2]) {
		memcpy(own, kcfg.led_rgb, 3);
	} else {
		own[0] = DEFAULT_R;
		own[1] = DEFAULT_G;
		own[2] = DEFAULT_B;
	}
	if (!have_applied || memcmp(own, applied, 3) != 0) {
		memcpy(applied, own, 3);
		memcpy(colour, own, 3);
		have_applied = 1;
	}
	shown = kcfg.led_fixed ? own : colour;

	switch (kcfg.led_mode) {
	case 1:   /* solid */
		level = kcfg.led_brightness;
		break;
	case 2: { /* breathing: triangle wave, period = led_breath * 100 ms */
		uint32_t period = (uint32_t)kcfg.led_breath * 100u;
		uint32_t phase = ms % period;
		uint32_t half = period / 2u;
		uint32_t tri = phase < half ? phase * 255u / half : (period - phase) * 255u / half;

		level = (unsigned)(tri * kcfg.led_brightness / 255u);
		break;
	}
	case 3:   /* only while the host has the device configured */
		level = active ? kcfg.led_brightness : 0;
		break;
	default:
		level = 0;
		break;
	}
	level = gamma_level(level);
	for (i = 0; i < 3; i++) {
		set_channel(i, shown[i] * level / 255u);
	}
}
