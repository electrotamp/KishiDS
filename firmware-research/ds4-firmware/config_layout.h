/* Output of tools/gen_config.py -- do not edit by hand. */
#ifndef CONFIG_LAYOUT_H
#define CONFIG_LAYOUT_H

#include <stddef.h>
#include <stdint.h>
#include <string.h>

#define KCFG_SIZE    256u
#define KCFG_VERSION 1u
#define KCFG_MAGIC   {'K', 'I', 'S', 'H', 'I', 'C', 'F', 'G'}

/* DS4 output codes (button_map values). */
enum kcfg_output {
	KO_NONE = 0,
	KO_SQUARE = 1,
	KO_CROSS = 2,
	KO_CIRCLE = 3,
	KO_TRIANGLE = 4,
	KO_L1 = 5,
	KO_R1 = 6,
	KO_L2 = 7,
	KO_R2 = 8,
	KO_SHARE = 9,
	KO_OPTIONS = 10,
	KO_L3 = 11,
	KO_R3 = 12,
	KO_PS = 13,
	KO_TOUCHPAD = 14,
	KO_DPADUP = 15,
	KO_DPADDOWN = 16,
	KO_DPADLEFT = 17,
	KO_DPADRIGHT = 18,
	KO_TOUCHLEFT = 19,
	KO_TOUCHRIGHT = 20,
	KO_COUNT = 21
};

/* Kishi V2 Pro default button mapping (board_defaults.h). */
#define KCFG_V2PRO_BUTTON_MAP  {2, 3, 1, 4, 15, 16, 17, 18, 5, 6, 11, 12, 10, 13, 14, 9}
#define KCFG_V2PRO_BUTTON_MAP2 {19, 20, 0, 0}

struct __attribute__((packed)) kishi_config {
	char magic[8];  /* 0: Marker used to find the block in the image */
	uint16_t version;  /* 8: Layout version */
	uint16_t size;  /* 10: Block size in bytes */
	uint32_t crc32;  /* 12: CRC-32 (ISO-HDLC) of bytes 16..size-1 */
	uint8_t button_map[16];  /* 16: DS4 output code for each Kishi button (index = scan order) */
	uint8_t stick_flags;  /* 32: bit0 invert LX, bit1 invert LY, bit2 invert RX, bit3 invert RY, bit4 swap sticks */
	uint8_t left_dead;  /* 33: Left stick deadzone, % of travel */
	uint8_t right_dead;  /* 34: Right stick deadzone, % of travel */
	uint8_t left_outer;  /* 35: Left stick full-scale point, % of travel */
	uint8_t right_outer;  /* 36: Right stick full-scale point, % of travel */
	uint8_t left_curve;  /* 37: Left stick response curve */
	uint8_t right_curve;  /* 38: Right stick response curve */
	uint8_t trig_thresh;  /* 39: Digital L2/R2 press threshold (0-255 of analog travel) */
	uint8_t l2_dead;  /* 40: L2 inner deadzone, % */
	uint8_t r2_dead;  /* 41: R2 inner deadzone, % */
	uint8_t l2_outer;  /* 42: L2 full-scale point, % */
	uint8_t r2_outer;  /* 43: R2 full-scale point, % */
	uint8_t trig_flags;  /* 44: bit0 swap L2/R2, bit1 digital-only triggers (0/255) */
	uint8_t dpad_mode;  /* 45: What the D-pad buttons drive */
	uint8_t socd_mode;  /* 46: Opposite D-pad directions pressed together */
	uint8_t led_mode;  /* 47: Blue LED behaviour */
	uint8_t led_brightness;  /* 48: Blue LED brightness */
	uint8_t led_breath;  /* 49: Breathing period in 0.1 s units */
	uint8_t poll_ms;  /* 50: USB interrupt polling interval in ms */
	uint8_t calib_mode;  /* 51: 0 = stock calibration page, 1 = calibration stored below */
	uint8_t rumble_level;  /* 52: Rumble strength at full DS4 level, % of the actuators' full scale (0 = board default) */
	uint8_t led_rgb[3];  /* 53: RGB LED colour (R, G, B; Kishi V2 Pro); 0, 0, 0 = blue */
	uint16_t cal_smax[4];  /* 56: Stick max raw (RX, RY, LX, LY) */
	uint16_t cal_smin[4];  /* 64: Stick min raw (RX, RY, LX, LY) */
	uint16_t cal_scenter[4];  /* 72: Stick centre raw (RX, RY, LX, LY) */
	uint16_t cal_t_lo[2];  /* 80: Trigger pressed raw (L2, R2) */
	uint16_t cal_t_hi[2];  /* 84: Trigger rest raw (L2, R2) */
	uint16_t vid;  /* 88: USB vendor ID (locked: DS4) */
	uint16_t pid;  /* 90: USB product ID (locked: DS4) */
	uint16_t bcd_device;  /* 92: USB device release (BCD) (locked) */
	uint8_t reserved2[2];  /* 94: reserved */
	char manufacturer[32];  /* 96: USB manufacturer string (locked) */
	char product[32];  /* 128: USB product string (user-visible device name) */
	char serial[32];  /* 160: USB serial string (locked: always the controller's factory serial, see kcfg_resolve_serial) */
	uint8_t led_fixed;  /* 192: RGB LED: 0 = games/apps may change the colour (DS4 lightbar), 1 = always led_rgb */
	uint8_t button_map2[4];  /* 193: DS4 output code for buttons 16..19 (Kishi V2 Pro: M1, M2) */
	uint8_t reserved3[59];  /* 197: reserved */
};

_Static_assert(sizeof(struct kishi_config) == 256, "config size");
_Static_assert(offsetof(struct kishi_config, magic) == 0, "magic offset");
_Static_assert(offsetof(struct kishi_config, version) == 8, "version offset");
_Static_assert(offsetof(struct kishi_config, size) == 10, "size offset");
_Static_assert(offsetof(struct kishi_config, crc32) == 12, "crc32 offset");
_Static_assert(offsetof(struct kishi_config, button_map) == 16, "button_map offset");
_Static_assert(offsetof(struct kishi_config, stick_flags) == 32, "stick_flags offset");
_Static_assert(offsetof(struct kishi_config, left_dead) == 33, "left_dead offset");
_Static_assert(offsetof(struct kishi_config, right_dead) == 34, "right_dead offset");
_Static_assert(offsetof(struct kishi_config, left_outer) == 35, "left_outer offset");
_Static_assert(offsetof(struct kishi_config, right_outer) == 36, "right_outer offset");
_Static_assert(offsetof(struct kishi_config, left_curve) == 37, "left_curve offset");
_Static_assert(offsetof(struct kishi_config, right_curve) == 38, "right_curve offset");
_Static_assert(offsetof(struct kishi_config, trig_thresh) == 39, "trig_thresh offset");
_Static_assert(offsetof(struct kishi_config, l2_dead) == 40, "l2_dead offset");
_Static_assert(offsetof(struct kishi_config, r2_dead) == 41, "r2_dead offset");
_Static_assert(offsetof(struct kishi_config, l2_outer) == 42, "l2_outer offset");
_Static_assert(offsetof(struct kishi_config, r2_outer) == 43, "r2_outer offset");
_Static_assert(offsetof(struct kishi_config, trig_flags) == 44, "trig_flags offset");
_Static_assert(offsetof(struct kishi_config, dpad_mode) == 45, "dpad_mode offset");
_Static_assert(offsetof(struct kishi_config, socd_mode) == 46, "socd_mode offset");
_Static_assert(offsetof(struct kishi_config, led_mode) == 47, "led_mode offset");
_Static_assert(offsetof(struct kishi_config, led_brightness) == 48, "led_brightness offset");
_Static_assert(offsetof(struct kishi_config, led_breath) == 49, "led_breath offset");
_Static_assert(offsetof(struct kishi_config, poll_ms) == 50, "poll_ms offset");
_Static_assert(offsetof(struct kishi_config, calib_mode) == 51, "calib_mode offset");
_Static_assert(offsetof(struct kishi_config, rumble_level) == 52, "rumble_level offset");
_Static_assert(offsetof(struct kishi_config, led_rgb) == 53, "led_rgb offset");
_Static_assert(offsetof(struct kishi_config, cal_smax) == 56, "cal_smax offset");
_Static_assert(offsetof(struct kishi_config, cal_smin) == 64, "cal_smin offset");
_Static_assert(offsetof(struct kishi_config, cal_scenter) == 72, "cal_scenter offset");
_Static_assert(offsetof(struct kishi_config, cal_t_lo) == 80, "cal_t_lo offset");
_Static_assert(offsetof(struct kishi_config, cal_t_hi) == 84, "cal_t_hi offset");
_Static_assert(offsetof(struct kishi_config, vid) == 88, "vid offset");
_Static_assert(offsetof(struct kishi_config, pid) == 90, "pid offset");
_Static_assert(offsetof(struct kishi_config, bcd_device) == 92, "bcd_device offset");
_Static_assert(offsetof(struct kishi_config, reserved2) == 94, "reserved2 offset");
_Static_assert(offsetof(struct kishi_config, manufacturer) == 96, "manufacturer offset");
_Static_assert(offsetof(struct kishi_config, product) == 128, "product offset");
_Static_assert(offsetof(struct kishi_config, serial) == 160, "serial offset");
_Static_assert(offsetof(struct kishi_config, led_fixed) == 192, "led_fixed offset");
_Static_assert(offsetof(struct kishi_config, button_map2) == 193, "button_map2 offset");
_Static_assert(offsetof(struct kishi_config, reserved3) == 197, "reserved3 offset");

/* Defaults without the magic: the fallback copy must not contain the marker bytes. */
#define KCFG_DEFAULTS_BODY \
	.version = 0x1, \
	.size = 0x100, \
	.button_map = {2, 3, 1, 4, 15, 16, 17, 18, 5, 6, 11, 12, 10, 13, 9, 0}, \
	.stick_flags = 0, \
	.left_dead = 8, \
	.right_dead = 8, \
	.left_outer = 0x5a, \
	.right_outer = 0x5a, \
	.left_curve = 0, \
	.right_curve = 0, \
	.trig_thresh = 0xa, \
	.l2_dead = 0, \
	.r2_dead = 0, \
	.l2_outer = 0x64, \
	.r2_outer = 0x64, \
	.trig_flags = 0, \
	.dpad_mode = 0, \
	.socd_mode = 0, \
	.led_mode = 1, \
	.led_brightness = 0xff, \
	.led_breath = 0x14, \
	.poll_ms = 5, \
	.calib_mode = 0, \
	.rumble_level = 0, \
	.led_rgb = {0, 0, 0}, \
	.cal_smax = {3264, 3317, 3395, 3360}, \
	.cal_smin = {566, 558, 718, 594}, \
	.cal_scenter = {1905, 2000, 2036, 1971}, \
	.cal_t_lo = {579, 542}, \
	.cal_t_hi = {1905, 1806}, \
	.vid = 0x54c, \
	.pid = 0x5c4, \
	.bcd_device = 0x100, \
	.manufacturer = "ElectroTamp KishiDS", \
	.product = "Wireless Controller", \
	.serial = "", \
	.led_fixed = 0, \
	.button_map2 = {0, 0, 0, 0}, \
	.crc32 = 0

#define KCFG_DEFAULTS { .magic = KCFG_MAGIC, KCFG_DEFAULTS_BODY }

/* Clamp every ranged field into its legal range (defence against a hand-edited block). */
static inline void kcfg_clamp(struct kishi_config *c)
{
	unsigned i;
	(void)i;
	for (i = 0; i < 16; i++) {
		if (c->button_map[i] > 20) c->button_map[i] = 20;
	}
	if (c->stick_flags > 31) c->stick_flags = 31;
	if (c->left_dead > 50) c->left_dead = 50;
	if (c->right_dead > 50) c->right_dead = 50;
	if (c->left_outer < 50) c->left_outer = 50;
	if (c->left_outer > 100) c->left_outer = 100;
	if (c->right_outer < 50) c->right_outer = 50;
	if (c->right_outer > 100) c->right_outer = 100;
	if (c->left_curve > 2) c->left_curve = 2;
	if (c->right_curve > 2) c->right_curve = 2;
	if (c->l2_dead > 50) c->l2_dead = 50;
	if (c->r2_dead > 50) c->r2_dead = 50;
	if (c->l2_outer < 50) c->l2_outer = 50;
	if (c->l2_outer > 100) c->l2_outer = 100;
	if (c->r2_outer < 50) c->r2_outer = 50;
	if (c->r2_outer > 100) c->r2_outer = 100;
	if (c->trig_flags > 3) c->trig_flags = 3;
	if (c->dpad_mode > 3) c->dpad_mode = 3;
	if (c->socd_mode > 1) c->socd_mode = 1;
	if (c->led_mode > 3) c->led_mode = 3;
	if (c->led_breath < 5) c->led_breath = 5;
	if (c->led_breath > 100) c->led_breath = 100;
	if (c->poll_ms < 1) c->poll_ms = 1;
	if (c->poll_ms > 8) c->poll_ms = 8;
	if (c->calib_mode > 1) c->calib_mode = 1;
	if (c->rumble_level > 100) c->rumble_level = 100;
	for (i = 0; i < 4; i++) {
		if (c->cal_smax[i] > 4095) c->cal_smax[i] = 4095;
	}
	for (i = 0; i < 4; i++) {
		if (c->cal_smin[i] > 4095) c->cal_smin[i] = 4095;
	}
	for (i = 0; i < 4; i++) {
		if (c->cal_scenter[i] > 4095) c->cal_scenter[i] = 4095;
	}
	for (i = 0; i < 2; i++) {
		if (c->cal_t_lo[i] > 4095) c->cal_t_lo[i] = 4095;
	}
	for (i = 0; i < 2; i++) {
		if (c->cal_t_hi[i] > 4095) c->cal_t_hi[i] = 4095;
	}
	c->vid = 0x54c;
	c->pid = 0x5c4;
	c->bcd_device = 0x100;
	memset(c->manufacturer, 0, sizeof(c->manufacturer));
	memcpy(c->manufacturer, "ElectroTamp KishiDS", 19);
	memset(c->serial, 0, sizeof(c->serial));
	memcpy(c->serial, "", 0);
	if (c->led_fixed > 1) c->led_fixed = 1;
	for (i = 0; i < 4; i++) {
		if (c->button_map2[i] > 20) c->button_map2[i] = 20;
	}
}

#endif
