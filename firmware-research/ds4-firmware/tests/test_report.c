/* Host unit tests for config validation and report building.  Run: tests/run_tests.sh */
#include <stdio.h>
#include <string.h>

#include "../ds4_usb.h"
#include "../report.h"

static int checks, failures;

#define CHECK(cond, ...) do { \
	checks++; \
	if (!(cond)) { failures++; printf("FAIL %s:%d: ", __FILE__, __LINE__); printf(__VA_ARGS__); printf("\n"); } \
} while (0)

#define EXPECT_EQ(a, b, name) CHECK((a) == (b), "%s: got %d, expected %d", name, (int)(a), (int)(b))

/* Stock calibration of the reference unit (matches the defaults in the config block). */
static struct kishi_config cfg_default(void)
{
	struct kishi_config c = KCFG_DEFAULTS;

	return c;
}

static void rest(uint16_t adc[KISHI_ADC_COUNT])
{
	/* Rest readings captured from the reference unit: RX, RY, R2, LY, LX, L2. */
	static const uint16_t v[KISHI_ADC_COUNT] = {2000, 2100, 1963, 2041, 2130, 2144};

	memcpy(adc, v, sizeof(v));
}

static struct kishi_cal cal_default(const struct kishi_config *c)
{
	struct kishi_cal cal;

	report_cal_from_config(&cal, c);
	return cal;
}

static void build(const struct kishi_config *c, uint32_t buttons, const uint16_t adc[KISHI_ADC_COUNT], uint8_t out[64])
{
	struct kishi_cal cal = cal_default(c);

	report_build(c, &cal, buttons, adc, 0, out);
}

static void test_crc_and_validation(void)
{
	struct kishi_config c = cfg_default();
	static const uint8_t vec[] = "123456789";

	EXPECT_EQ(kcfg_crc32(vec, 9), 0xCBF43926u, "crc32 check vector");
	CHECK(!kcfg_block_valid(&c), "block with crc 0 must be invalid");
	c.crc32 = kcfg_crc32((const uint8_t *)&c + 16, KCFG_SIZE - 16);
	CHECK(kcfg_block_valid(&c), "block with correct crc must be valid");
	c.left_dead ^= 1;
	CHECK(!kcfg_block_valid(&c), "a changed byte must invalidate the crc");
	c.left_dead ^= 1;
	c.version = 2;
	CHECK(!kcfg_block_valid(&c), "wrong version must be invalid");
	c.version = KCFG_VERSION;
	c.magic[0] = 'X';
	CHECK(!kcfg_block_valid(&c), "wrong magic must be invalid");
}

static void test_clamp(void)
{
	struct kishi_config c = cfg_default();

	c.left_dead = 200; c.left_outer = 3; c.led_mode = 99; c.poll_ms = 0; c.button_map[0] = 250;
	kcfg_clamp(&c);
	EXPECT_EQ(c.left_dead, 50, "left_dead clamps high");
	EXPECT_EQ(c.left_outer, 50, "left_outer clamps low");
	EXPECT_EQ(c.led_mode, 3, "led_mode clamps");
	EXPECT_EQ(c.poll_ms, 1, "poll_ms clamps");
	EXPECT_EQ(c.button_map[0], KO_COUNT - 1, "button_map clamps");
}

static void test_defaults_match_hardware_behaviour(void)
{
	struct kishi_config c = cfg_default();
	uint16_t adc[KISHI_ADC_COUNT];
	uint8_t r[64];
	int i;

	rest(adc);
	build(&c, 0, adc, r);
	EXPECT_EQ(r[0], 1, "report id");
	for (i = 1; i <= 4; i++) {
		EXPECT_EQ(r[i], 128, "stick at rest");
	}
	EXPECT_EQ(r[5], 8, "hat neutral");
	EXPECT_EQ(r[6], 0, "no buttons");
	EXPECT_EQ(r[8], 0, "L2 rest");
	EXPECT_EQ(r[9], 0, "R2 rest");
	EXPECT_EQ(r[30], 0x1B, "battery byte");

	/* Default button mapping == what was verified on the hardware. */
	static const struct { int kb; int byte; int bit; } exp[] = {
		{KB_X, 5, 0x10}, {KB_A, 5, 0x20}, {KB_B, 5, 0x40}, {KB_Y, 5, 0x80},
		{KB_L1, 6, 0x01}, {KB_R1, 6, 0x02}, {KB_LFUNC, 6, 0x10}, {KB_RFUNC, 6, 0x20},
		{KB_L3, 6, 0x40}, {KB_R3, 6, 0x80}, {KB_HOME, 7, 0x01},
	};
	for (i = 0; i < (int)(sizeof(exp) / sizeof(exp[0])); i++) {
		build(&c, (uint16_t)(1u << exp[i].kb), adc, r);
		CHECK((r[exp[i].byte] & exp[i].bit) == exp[i].bit, "button %d -> byte %d bit %02x", exp[i].kb, exp[i].byte, exp[i].bit);
	}
	static const struct { int kb; int hat; } dp[] = {{KB_UP, 0}, {KB_DOWN, 4}, {KB_LEFT, 6}, {KB_RIGHT, 2}};
	for (i = 0; i < 4; i++) {
		build(&c, (uint16_t)(1u << dp[i].kb), adc, r);
		EXPECT_EQ(r[5] & 15, dp[i].hat, "dpad hat");
	}
	build(&c, (1u << KB_UP) | (1u << KB_RIGHT), adc, r);
	EXPECT_EQ(r[5] & 15, 1, "up+right diagonal");
}

static void test_sticks(void)
{
	struct kishi_config c = cfg_default();
	uint16_t adc[KISHI_ADC_COUNT];
	uint8_t r[64];

	/* Full deflection (reference unit's calibrated extremes). */
	rest(adc); adc[4] = 3395; build(&c, 0, adc, r); EXPECT_EQ(r[1], 255, "LX max");
	rest(adc); adc[4] = 718;  build(&c, 0, adc, r); EXPECT_EQ(r[1], 1, "LX min");
	rest(adc); adc[3] = 3360; build(&c, 0, adc, r); EXPECT_EQ(r[2], 1, "LY raw max is stick UP (byte low)");
	rest(adc); adc[3] = 594;  build(&c, 0, adc, r); EXPECT_EQ(r[2], 255, "LY raw min is stick DOWN (byte high)");
	rest(adc); adc[0] = 3264; build(&c, 0, adc, r); EXPECT_EQ(r[3], 255, "RX max");
	rest(adc); adc[1] = 3317; build(&c, 0, adc, r); EXPECT_EQ(r[4], 255, "RY max");

	/* Deadzone: a small move stays at 128, a bigger one leaves it. */
	rest(adc); adc[4] = 2036 + 50; build(&c, 0, adc, r); EXPECT_EQ(r[1], 128, "inside deadzone");
	rest(adc); adc[4] = 2036 + 400; build(&c, 0, adc, r); CHECK(r[1] > 128, "outside deadzone moves");

	/* A larger deadzone swallows that move. */
	c.left_dead = 30;
	build(&c, 0, adc, r); EXPECT_EQ(r[1], 128, "30% deadzone swallows 400 counts");
	c = cfg_default();

	/* Inversion, swap. */
	c.stick_flags = 0x01;
	rest(adc); adc[4] = 3395; build(&c, 0, adc, r); EXPECT_EQ(r[1], 1, "invert LX");
	c.stick_flags = 0x10;
	rest(adc); adc[4] = 3395; build(&c, 0, adc, r); EXPECT_EQ(r[3], 255, "swap: left stick drives right X");
	EXPECT_EQ(r[1], 128, "swap: left X now idle");

	/* Curves keep the endpoints and bend the middle. */
	c = cfg_default();
	rest(adc); adc[4] = 2036 + 700;
	build(&c, 0, adc, r); int lin = r[1];
	c.left_curve = 1; build(&c, 0, adc, r); int soft = r[1];
	c.left_curve = 2; build(&c, 0, adc, r); int hard = r[1];
	CHECK(soft < lin && lin < hard, "curves ordered precise < linear < aggressive (%d %d %d)", soft, lin, hard);
	c.left_curve = 1; rest(adc); adc[4] = 3395; build(&c, 0, adc, r); EXPECT_EQ(r[1], 255, "precise keeps full scale");
}

static void test_triggers(void)
{
	struct kishi_config c = cfg_default();
	uint16_t adc[KISHI_ADC_COUNT];
	uint8_t r[64];

	rest(adc); adc[5] = 579;  build(&c, 0, adc, r); EXPECT_EQ(r[8], 255, "L2 fully pressed");
	CHECK(r[6] & 0x04, "L2 digital bit when pressed");
	rest(adc); adc[2] = 542;  build(&c, 0, adc, r); EXPECT_EQ(r[9], 255, "R2 fully pressed");
	CHECK(r[6] & 0x08, "R2 digital bit when pressed");
	rest(adc); adc[5] = (579 + 1905) / 2; build(&c, 0, adc, r); CHECK(r[8] > 100 && r[8] < 155, "L2 half pressed ~127 (%d)", r[8]);

	c.trig_flags = 0x01; rest(adc); adc[5] = 579; build(&c, 0, adc, r);
	EXPECT_EQ(r[9], 255, "swap: L2 sensor drives R2"); EXPECT_EQ(r[8], 0, "swap: L2 idle");
	c.trig_flags = 0x02; rest(adc); adc[5] = (579 + 1905) / 2; build(&c, 0, adc, r);
	EXPECT_EQ(r[8], 255, "digital-only snaps to 255");
	c = cfg_default();
	c.l2_dead = 50; rest(adc); adc[5] = (579 + 1905) / 2 + 100; build(&c, 0, adc, r);
	EXPECT_EQ(r[8], 0, "inner deadzone swallows light press");
	c = cfg_default(); c.button_map[KB_A] = KO_L2;
	rest(adc); build(&c, 1u << KB_A, adc, r);
	EXPECT_EQ(r[8], 255, "button mapped to L2 forces analog 255"); CHECK(r[6] & 0x04, "mapped L2 sets digital");
	EXPECT_EQ(r[5] & 0x20, 0, "A no longer Cross");
}

static void test_remap_and_dpad(void)
{
	struct kishi_config c = cfg_default();
	uint16_t adc[KISHI_ADC_COUNT];
	uint8_t r[64];

	rest(adc);
	c.button_map[KB_A] = KO_CIRCLE; c.button_map[KB_B] = KO_CROSS;
	build(&c, 1u << KB_A, adc, r); EXPECT_EQ(r[5] & 0xF0, 0x40, "A -> Circle");
	build(&c, 1u << KB_B, adc, r); EXPECT_EQ(r[5] & 0xF0, 0x20, "B -> Cross");
	c = cfg_default(); c.button_map[KB_HOME] = KO_NONE;
	build(&c, 1u << KB_HOME, adc, r); EXPECT_EQ(r[7] & 3, 0, "disabled button does nothing");
	c.button_map[KB_HOME] = KO_TOUCHPAD; build(&c, 1u << KB_HOME, adc, r); EXPECT_EQ(r[7] & 3, 2, "Home -> Touchpad click");
	c = cfg_default(); c.button_map[KB_LFUNC] = KO_DPADUP;
	build(&c, 1u << KB_LFUNC, adc, r); EXPECT_EQ(r[5] & 15, 0, "any button can be mapped to a D-pad direction");

	/* SOCD */
	c = cfg_default();
	build(&c, (1u << KB_UP) | (1u << KB_DOWN), adc, r); EXPECT_EQ(r[5] & 15, 8, "SOCD neutral: up+down");
	build(&c, (1u << KB_LEFT) | (1u << KB_RIGHT), adc, r); EXPECT_EQ(r[5] & 15, 8, "SOCD neutral: left+right");
	c.socd_mode = 1;
	build(&c, (1u << KB_UP) | (1u << KB_DOWN), adc, r); EXPECT_EQ(r[5] & 15, 0, "SOCD up wins");
	build(&c, (1u << KB_LEFT) | (1u << KB_RIGHT), adc, r); EXPECT_EQ(r[5] & 15, 2, "SOCD right wins");

	/* D-pad as stick */
	c = cfg_default(); c.dpad_mode = 1;
	build(&c, 1u << KB_UP, adc, r); EXPECT_EQ(r[5] & 15, 8, "dpad->stick: hat neutral"); EXPECT_EQ(r[2], 1, "dpad->left stick up");
	build(&c, 1u << KB_RIGHT, adc, r); EXPECT_EQ(r[1], 255, "dpad->left stick right");
	c.dpad_mode = 2; build(&c, 1u << KB_DOWN, adc, r); EXPECT_EQ(r[4], 255, "dpad->right stick down");
	c.dpad_mode = 3; build(&c, 1u << KB_DOWN, adc, r); EXPECT_EQ(r[5] & 15, 8, "dpad disabled: hat neutral"); EXPECT_EQ(r[4], 128, "dpad disabled: no stick");
}

static void test_stock_cal_page(void)
{
	struct kishi_cal cal;
	uint8_t page[0x59];

	memset(page, 0, sizeof(page));
	CHECK(!report_cal_from_stock_page(&cal, page), "zeroed page is invalid");
	/* The reference unit's page (dumped from hardware). */
	static const uint8_t ref[0x59] = {
		0xc0, 0x0c, 0x36, 0x02, 0xf5, 0x0c, 0x2e, 0x02, 0x43, 0x0d, 0xce, 0x02, 0x20, 0x0d, 0x52, 0x02,
		0xc1, 0x07, 0xcb, 0x01, 0x5e, 0x07, 0xa6, 0x01, 0x6e, 0x00, 0x0a, 0x0a, 0x78, 0x00, 0xb0, 0xff,
		0x78, 0x00, 0xb0, 0xff, [0x34] = 0xf4, 0x07, 0xb3, 0x07, 0x71, 0x07, 0xd0, 0x07, 0x66, 0x66, 0x66, 0x3f,
		[0x55] = 0x29, 0x73, 0xd0, 0x2d,
	};
	CHECK(report_cal_from_stock_page(&cal, ref), "reference page is valid");
	EXPECT_EQ(cal.smax[2], 3395, "stock page LX max"); EXPECT_EQ(cal.smin[2], 718, "stock page LX min");
	EXPECT_EQ(cal.scenter[0], 1905, "stock page RX centre"); EXPECT_EQ(cal.scenter[2], 2036, "stock page LX centre");
	EXPECT_EQ(cal.t_hi[0], 1905, "stock page L2 rest"); EXPECT_EQ(cal.t_lo[0], 579, "stock page L2 pressed");
	EXPECT_EQ(cal.t_hi[1], 1806, "stock page R2 rest"); EXPECT_EQ(cal.t_lo[1], 542, "stock page R2 pressed");

	/* Defaults in the config block equal the stock page values (so the fallback is faithful). */
	struct kishi_config c = cfg_default();
	struct kishi_cal d;
	report_cal_from_config(&d, &c);
	CHECK(memcmp(&cal, &d, sizeof(cal)) == 0, "config defaults == reference stock calibration");
}

/* Per-unit DS4 address (ds4_usb.c) and the touchpad packet TouchLeft / TouchRight produce (report.c). */
static void test_address_and_touch(void)
{
	static const uint8_t uid16[16] = {0x5A, 0x74, 0x36, 0x7F, 0xFC, 0x1B, 0x4E, 0x59, 0xA8, 0x49, 0x42, 0x04,
					  0x54, 0x9F, 0x29, 0x23};
	/* Read on the V2 Pro whose UUID is uid16: 0x12 answered 40 b2 b7 15 be 1e. */
	static const uint8_t want16[6] = {0x40, 0xB2, 0xB7, 0x15, 0xBE, 0x1E};
	uint8_t a[6], out[64];
	uint16_t adc[KISHI_ADC_COUNT] = {2048, 2048, 2048, 2048, 2048, 2048};
	struct kishi_config c = cfg_default();
	unsigned i;

	ds4_address_from_uid(uid16, 16, a);
	CHECK(memcmp(a, want16, 6) == 0, "address from a 128-bit UUID matches the one read on hardware");
	ds4_address_from_uid(uid16, 12, a);
	for (i = 0; i < 6; i++) {
		uint8_t w = (uint8_t)(uid16[i] ^ uid16[i + 6]);

		if (i == 5) w = (uint8_t)((w & 0xFC) | 0x02);
		EXPECT_EQ(a[i], w, "address from a 96-bit UID");
	}
	CHECK((a[5] & 3) == 2, "address is unicast and locally administered");

	build(&c, 0, adc, out);
	CHECK(out[33] == 1 && (out[35] & 0x80) && (out[39] & 0x80), "touch packet with no finger at rest");
	c.button_map[0] = KO_TOUCHLEFT;
	c.button_map2[1] = KO_TOUCHRIGHT;
	build(&c, 1u, adc, out);
	CHECK((out[7] & 2) && !(out[35] & 0x80) && (out[36] | (out[37] & 0xF) << 8) == 480, "TouchLeft: click + finger at x 480");
	build(&c, 1ul << 17, adc, out);
	CHECK((out[7] & 2) && !(out[35] & 0x80) && (out[36] | (out[37] & 0xF) << 8) == 1440, "button 17 through button_map2: x 1440");
}

int main(void)
{
	test_crc_and_validation();
	test_clamp();
	test_defaults_match_hardware_behaviour();
	test_sticks();
	test_triggers();
	test_remap_and_dpad();
	test_stock_cal_page();
	test_address_and_touch();
	printf("%d checks, %d failures\n", checks, failures);
	return failures ? 1 : 0;
}
