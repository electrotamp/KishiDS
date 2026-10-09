/*
 * Kishi V2 Pro (RZ06-0458) board layer.  Pin data from ../BOARD_MAP_V2PRO.md (Razer firmware 2.2, scanner at
 * 0x192ea, BOARD_InitPins at 0x153ce).
 */
#include "board.h"

#include "adc.h"
#include "flash.h"
#include "lpc55.h"

struct pin {
	uint8_t port, pin;
};

/*
 * Button pins.  Pins are certain (read from Razer's scanner); names were first taken with tools/v2pro_probe.py buttons
 * (2026-10-07), then corrected on an iPhone (2026-10-09): X/Y and Share/Nexus were the other way round.  All are
 * active-low with pull-ups.
 */
static const struct pin button_pins[VB_COUNT] = {
	[VB_A]     = {0, 4},
	[VB_B]     = {0, 6},
	[VB_X]     = {0, 8},
	[VB_Y]     = {0, 9},
	[VB_UP]    = {1, 7},
	[VB_DOWN]  = {0, 24},
	[VB_LEFT]  = {1, 17},
	[VB_RIGHT] = {0, 22},
	[VB_L1]    = {0, 0},
	[VB_R1]    = {0, 17},
	[VB_L3]    = {1, 14},
	[VB_R3]    = {0, 18},
	[VB_M1]    = {0, 27},   /* left programmable button */
	[VB_M2]    = {0, 31},   /* right programmable button */
	[VB_VIEW]  = {1, 26},   /* also Razer's table at 0x1D61C (XInput Back) */
	[VB_MENU]  = {0, 19},   /* also Razer's table at 0x1D61C (XInput Start) */
	[VB_HOME]  = {1, 6},    /* Nexus (game controller icon) */
	[VB_SHARE] = {1, 5},    /* screenshot icon; Razer's table at 0x1D61C maps it to an extra output bit */
};

/*
 * Analog inputs, in the shared raw[] order report.c expects (kishi_io.h: RX, RY, R2, LY, LX, L2).
 *
 * Certain (Razer firmware 2.2): the pins and their ADC channels (LPC55 pin functions; Razer's command table at
 * 0x1bac4), and the grouping: Razer builds one stick from P1_8 + P1_0, the other from P0_16 + P0_23, and the two
 * triggers from P0_10 and P0_15 (analog task at 0x16a14, sticks via 0xc860, triggers via 0xed42).
 * Confirmed on hardware (2026-10-07, v2pro_probe.py raw with our firmware, device-backups/phase5_raw.txt): every
 * pin below is the axis it is listed as.  Rest ~2000 on the sticks, L2 ~1640, R2 ~1735.  Directions: LX and RX rise
 * to the right; LY falls going up and RY rises going up; both triggers fall when pressed (to ~50 / ~140).
 *
 * report.c (shared with the V1) expects the V1's polarity: it negates LY and not RY, i.e. it wants LY rising and RY
 * falling going up.  Both Y axes are the other way round here, so they are mirrored (4095 - raw) below.  Everything
 * above report.c (telemetry 0xAB, KishiDS calibration) sees the mirrored values, so calibration stays consistent.
 */
static const struct analog {
	struct pin pin;
	struct adc_channel adc;
	uint8_t mirror;
} analog[KISHI_ADC_COUNT] = {
	{{0, 16}, {0, 1}, 0},   /* raw[0] RX: ADC0_8  (0B) */
	{{0, 23}, {0, 0}, 1},   /* raw[1] RY: ADC0_0  (0A), rises going up: mirrored */
	{{0, 15}, {2, 0}, 0},   /* raw[2] R2: ADC0_2  (2A) */
	{{1, 0},  {3, 1}, 1},   /* raw[3] LY: ADC0_11 (3B), falls going up: mirrored */
	{{1, 8},  {4, 0}, 0},   /* raw[4] LX: ADC0_4  (4A) */
	{{0, 10}, {1, 0}, 0},   /* raw[5] L2: ADC0_1  (1A) */
};

/* GPIO outputs Razer sets high at start-up (see board_init). */
static const struct pin razer_high_pins[] = {{1, 18}, {1, 12}, {1, 9}};

static uint16_t adc_raw[KISHI_ADC_COUNT] = {2048, 2048, 2048, 2048, 2048, 2048};

static void button_pin_init(const struct pin *p)
{
	IOCON_PIO(p->port, p->pin) = IOCON_FUNC0 | IOCON_DIGIMODE | IOCON_MODE_PULLUP;
	GPIO_DIR(p->port) &= ~(1u << p->pin);
}

static int pressed(const struct pin *p)
{
	return GPIO_B(p->port, p->pin) == 0;
}

int board_boot_combo_held(void)
{
	const struct pin *view = &button_pins[VB_VIEW], *menu = &button_pins[VB_MENU];
	unsigned sample;

	SYSCON_AHBCLKCTRLSET0 = AHBCLK0_IOCON | AHBCLK0_GPIO0 | AHBCLK0_GPIO1;
	button_pin_init(view);
	button_pin_init(menu);
	/* 8 samples about 1 ms apart on the 12 MHz reset clock: the pull-ups settle and a bounce cannot pass. */
	for (sample = 0; sample < 8; sample++) {
		volatile uint32_t n;

		for (n = 0; n < 2000u; n++) {
		}
		if (!pressed(view) || !pressed(menu)) {
			return 0;
		}
	}
	return 1;
}

void board_init(void)
{
	unsigned i;

	SYSCON_AHBCLKCTRLSET0 = AHBCLK0_IOCON | AHBCLK0_GPIO0 | AHBCLK0_GPIO1;

	for (i = 0; i < VB_COUNT; i++) {
		button_pin_init(&button_pins[i]);
	}
	/*
	 * Razer drives P1_18, P1_12, P1_9, P0_2, P0_3 and P1_25 high at start-up (BOARD_InitPins).  The last three have a
	 * peripheral function in IOCON there, so their GPIO level never reaches the pin and they stay alone here.  The first
	 * three are plain GPIO outputs: without them the sticks and triggers read near 0 on hardware (the first USB-working
	 * run, 2026-10-07), so one of them most likely powers the potentiometers.  Level first, then direction: no glitch.
	 */
	for (i = 0; i < sizeof(razer_high_pins) / sizeof(razer_high_pins[0]); i++) {
		const struct pin *p = &razer_high_pins[i];

		IOCON_PIO(p->port, p->pin) = IOCON_FUNC0 | IOCON_DIGIMODE;
		GPIO_SET(p->port) = 1u << p->pin;
		GPIO_DIR(p->port) |= 1u << p->pin;
	}
	{
		struct adc_channel channels[KISHI_ADC_COUNT];

		for (i = 0; i < KISHI_ADC_COUNT; i++) {
			IOCON_PIO(analog[i].pin.port, analog[i].pin.pin) = IOCON_FUNC0 | IOCON_ASW;
			channels[i] = analog[i].adc;
		}
		adc_init(channels, KISHI_ADC_COUNT);
	}
}

uint32_t board_read_buttons(void)
{
	uint32_t mask = 0;
	unsigned i;

	for (i = 0; i < VB_COUNT; i++) {
		if (pressed(&button_pins[i])) {
			mask |= 1u << i;
		}
	}
	return mask;
}

uint32_t board_to_kishi_buttons(uint32_t b)
{
	static const uint8_t to_kishi[VB_COUNT] = {
		[VB_A] = KB_A, [VB_B] = KB_B, [VB_X] = KB_X, [VB_Y] = KB_Y,
		[VB_UP] = KB_UP, [VB_DOWN] = KB_DOWN, [VB_LEFT] = KB_LEFT, [VB_RIGHT] = KB_RIGHT,
		[VB_L1] = KB_L1, [VB_R1] = KB_R1, [VB_L3] = KB_L3, [VB_R3] = KB_R3,
		[VB_VIEW] = KB_LFUNC, [VB_MENU] = KB_RFUNC, [VB_HOME] = KB_HOME,
		/* Share takes the config's spare 16th button_map slot (the V1 has 15 buttons). */
		[VB_SHARE] = KB_V2PRO_SHARE,
		/* M1 and M2 go through button_map2 (default: touchpad left / right click, board_defaults.h). */
		[VB_M1] = KB_V2PRO_M1, [VB_M2] = KB_V2PRO_M2,
	};
	uint32_t out = 0;
	unsigned i;

	for (i = 0; i < VB_COUNT; i++) {
		if ((b & (1u << i)) && to_kishi[i] != KB_NONE) {
			out |= 1ul << to_kishi[i];
		}
	}
	return out;
}

void board_read_adc(uint16_t out[KISHI_ADC_COUNT])
{
	unsigned i;

	(void)adc_poll(adc_raw);   /* keeps the last complete scan; mid-scale until the first one finishes */
	for (i = 0; i < KISHI_ADC_COUNT; i++) {
		out[i] = analog[i].mirror ? (uint16_t)(4095u - (adc_raw[i] & 4095u)) : adc_raw[i];
	}
}

int board_uid(uint8_t out[16])
{
	unsigned i, same = 1;

	if (flash_read_safe(0x0009FC70u, out, 16)) {
		return -1;
	}
	for (i = 1; i < 16; i++) {
		same &= out[i] == out[0];
	}
	return same ? -1 : 0;   /* all 0x00 or all 0xFF: not programmed */
}
