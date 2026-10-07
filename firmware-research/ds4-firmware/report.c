#include <string.h>

#include "report.h"

static uint16_t u16le(const uint8_t *p, unsigned off)
{
	return (uint16_t)(p[off] | (p[off + 1] << 8));
}

void report_cal_from_config(struct kishi_cal *cal, const struct kishi_config *cfg)
{
	unsigned i;

	for (i = 0; i < 4; i++) {
		cal->smax[i] = cfg->cal_smax[i];
		cal->smin[i] = cfg->cal_smin[i];
		cal->scenter[i] = cfg->cal_scenter[i];
	}
	for (i = 0; i < 2; i++) {
		cal->t_lo[i] = cfg->cal_t_lo[i];
		cal->t_hi[i] = cfg->cal_t_hi[i];
	}
}

int report_cal_from_stock_page(struct kishi_cal *cal, const uint8_t *page)
{
	uint32_t magic = (uint32_t)page[0x55] | ((uint32_t)page[0x56] << 8) |
			 ((uint32_t)page[0x57] << 16) | ((uint32_t)page[0x58] << 24);

	if ((uint32_t)(magic + 0xD22F8CD7u) != 0) {
		return 0;
	}
	/* Stock layout: max/min pairs for RX, RY, LX, LY at 0x00..0x0E; centres at 0x38, 0x3A, 0x34, 0x36. */
	cal->smax[0] = u16le(page, 0x00); cal->smin[0] = u16le(page, 0x02); cal->scenter[0] = u16le(page, 0x38);
	cal->smax[1] = u16le(page, 0x04); cal->smin[1] = u16le(page, 0x06); cal->scenter[1] = u16le(page, 0x3A);
	cal->smax[2] = u16le(page, 0x08); cal->smin[2] = u16le(page, 0x0A); cal->scenter[2] = u16le(page, 0x34);
	cal->smax[3] = u16le(page, 0x0C); cal->smin[3] = u16le(page, 0x0E); cal->scenter[3] = u16le(page, 0x36);
	/* Trigger offsets are stored as signed 16-bit; the uint16 wrap-around is intentional. */
	cal->t_hi[0] = (uint16_t)(u16le(page, 0x10) + u16le(page, 0x1E));
	cal->t_lo[0] = (uint16_t)(u16le(page, 0x12) + u16le(page, 0x1C));
	cal->t_hi[1] = (uint16_t)(u16le(page, 0x14) + u16le(page, 0x22));
	cal->t_lo[1] = (uint16_t)(u16le(page, 0x16) + u16le(page, 0x20));
	return 1;
}

/* Signed -127..127: deadzone around the centre, full scale at outer_pct of the remaining travel. */
static int stick_scale(uint16_t raw, uint16_t max, uint16_t min, uint16_t center,
		       unsigned dead_pct, unsigned outer_pct, unsigned curve)
{
	int32_t dead = ((int32_t)max - (int32_t)min) * (int32_t)dead_pct / 200;
	int32_t span, v, th, out, mag;

	if (raw > center) {
		th = (int32_t)center + dead;
		v = raw < max ? raw : max;
		if (v < th) {
			v = th;
		}
		span = ((int32_t)max - th) * (int32_t)outer_pct / 100;
		out = span > 0 ? 127 * (v - th) / span : 0;
	} else {
		th = (int32_t)center - dead;
		v = raw > min ? raw : min;
		if (v > th) {
			v = th;
		}
		span = (th - (int32_t)min) * (int32_t)outer_pct / 100;
		out = span > 0 ? -127 * (th - v) / span : 0;
	}
	if (out > 127) {
		out = 127;
	}
	if (out < -127) {
		out = -127;
	}

	mag = out < 0 ? -out : out;
	if (curve == 1) {            /* Precise: gentle near the centre */
		mag = mag * mag / 127;
	} else if (curve == 2) {     /* Aggressive: strong near the centre */
		mag = 2 * mag - mag * mag / 127;
	}
	if (mag > 127) {
		mag = 127;
	}
	return out < 0 ? (int)-mag : (int)mag;
}

/* Trigger sensors read HIGH at rest and fall when pressed; return 0 (rest)..255 (fully pressed). */
static uint8_t trigger_scale(uint16_t raw, uint16_t lo, uint16_t hi, unsigned inner_pct, unsigned outer_pct)
{
	int32_t range, pressed, inner, eff, v, out;

	if (hi <= lo) {
		return 0;
	}
	range = (int32_t)hi - (int32_t)lo;
	v = raw < lo ? lo : raw > hi ? hi : raw;
	pressed = (int32_t)hi - v;                       /* 0 at rest .. range fully pressed */
	inner = range * (int32_t)inner_pct / 100;
	eff = range * (int32_t)outer_pct / 100 - inner;
	if (eff <= 0 || pressed <= inner) {
		return 0;
	}
	out = 255 * (pressed - inner) / eff;
	return (uint8_t)(out > 255 ? 255 : out);
}

struct state {
	uint8_t face;          /* DS4 byte 5 bits 4..7 */
	uint8_t btn;           /* DS4 byte 6 */
	uint8_t misc;          /* DS4 byte 7 bits 0..1 (PS, touchpad click) */
	uint8_t up, down, left, right;
	uint8_t l2_force, r2_force;
};

static void apply_output(struct state *s, unsigned code)
{
	switch (code) {
	case KO_SQUARE:    s->face |= 0x10; break;
	case KO_CROSS:     s->face |= 0x20; break;
	case KO_CIRCLE:    s->face |= 0x40; break;
	case KO_TRIANGLE:  s->face |= 0x80; break;
	case KO_L1:        s->btn |= 0x01; break;
	case KO_R1:        s->btn |= 0x02; break;
	case KO_L2:        s->l2_force = 1; break;
	case KO_R2:        s->r2_force = 1; break;
	case KO_SHARE:     s->btn |= 0x10; break;
	case KO_OPTIONS:   s->btn |= 0x20; break;
	case KO_L3:        s->btn |= 0x40; break;
	case KO_R3:        s->btn |= 0x80; break;
	case KO_PS:        s->misc |= 0x01; break;
	case KO_TOUCHPAD:  s->misc |= 0x02; break;
	case KO_DPADUP:    s->up = 1; break;
	case KO_DPADDOWN:  s->down = 1; break;
	case KO_DPADLEFT:  s->left = 1; break;
	case KO_DPADRIGHT: s->right = 1; break;
	default: break;
	}
}

static uint8_t hat_value(const struct state *s)
{
	return s->up ? (s->right ? 1 : s->left ? 7 : 0)
	     : s->down ? (s->right ? 3 : s->left ? 5 : 4)
	     : (s->right ? 2 : s->left ? 6 : 8);
}

void report_build(const struct kishi_config *cfg, const struct kishi_cal *cal, uint16_t buttons,
		  const uint16_t adc[KISHI_ADC_COUNT], uint8_t counter, uint8_t out[KISHI_REPORT_SIZE])
{
	struct state s;
	int rx, ry, lx, ly, t;
	unsigned i, l2, r2;
	uint8_t *r = out;

	memset(&s, 0, sizeof(s));
	memset(out, 0, KISHI_REPORT_SIZE);

	for (i = 0; i < KB_COUNT; i++) {
		if (buttons & (1u << i)) {
			apply_output(&s, cfg->button_map[i]);
		}
	}

	/* Opposite directions pressed together. */
	if (cfg->socd_mode == 1) {
		if (s.up && s.down) {
			s.down = 0;
		}
		if (s.left && s.right) {
			s.left = 0;
		}
	} else {
		if (s.up && s.down) {
			s.up = s.down = 0;
		}
		if (s.left && s.right) {
			s.left = s.right = 0;
		}
	}

	rx = stick_scale(adc[0], cal->smax[0], cal->smin[0], cal->scenter[0], cfg->right_dead, cfg->right_outer, cfg->right_curve);
	ry = stick_scale(adc[1], cal->smax[1], cal->smin[1], cal->scenter[1], cfg->right_dead, cfg->right_outer, cfg->right_curve);
	lx = stick_scale(adc[4], cal->smax[2], cal->smin[2], cal->scenter[2], cfg->left_dead, cfg->left_outer, cfg->left_curve);
	ly = -stick_scale(adc[3], cal->smax[3], cal->smin[3], cal->scenter[3], cfg->left_dead, cfg->left_outer, cfg->left_curve);

	if (cfg->stick_flags & 0x10) {   /* swap sticks */
		t = lx; lx = rx; rx = t;
		t = ly; ly = ry; ry = t;
	}
	if (cfg->stick_flags & 0x01) lx = -lx;
	if (cfg->stick_flags & 0x02) ly = -ly;
	if (cfg->stick_flags & 0x04) rx = -rx;
	if (cfg->stick_flags & 0x08) ry = -ry;

	/* What the D-pad drives. */
	if (cfg->dpad_mode == 0) {
		r[5] = hat_value(&s);
	} else {
		int dx = s.right ? 127 : s.left ? -127 : 0;
		int dy = s.down ? 127 : s.up ? -127 : 0;

		r[5] = 8;                       /* hat neutral */
		if (cfg->dpad_mode == 1) {
			if (dx) lx = dx;
			if (dy) ly = dy;
		} else if (cfg->dpad_mode == 2) {
			if (dx) rx = dx;
			if (dy) ry = dy;
		}                               /* mode 3: ignore the D-pad */
	}

	r[0] = 0x01;
	r[1] = (uint8_t)(lx + 128);
	r[2] = (uint8_t)(ly + 128);
	r[3] = (uint8_t)(rx + 128);
	r[4] = (uint8_t)(ry + 128);
	r[5] |= s.face;

	l2 = trigger_scale(adc[5], cal->t_lo[0], cal->t_hi[0], cfg->l2_dead, cfg->l2_outer);
	r2 = trigger_scale(adc[2], cal->t_lo[1], cal->t_hi[1], cfg->r2_dead, cfg->r2_outer);
	if (cfg->trig_flags & 0x01) {    /* swap triggers */
		i = l2; l2 = r2; r2 = i;
	}
	if (cfg->trig_flags & 0x02) {    /* digital-only */
		l2 = l2 > cfg->trig_thresh ? 255 : 0;
		r2 = r2 > cfg->trig_thresh ? 255 : 0;
	}
	if (s.l2_force) l2 = 255;
	if (s.r2_force) r2 = 255;
	r[8] = (uint8_t)l2;
	r[9] = (uint8_t)r2;

	r[6] = s.btn;
	if (l2 > cfg->trig_thresh) r[6] |= 0x04;
	if (r2 > cfg->trig_thresh) r[6] |= 0x08;

	r[7] = (uint8_t)(s.misc | (counter << 2));
	r[30] = 0x1B;                    /* wired, battery full */
}
