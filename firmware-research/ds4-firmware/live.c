#include <string.h>

#include "config.h"
#include "live.h"

#define CHUNK 32u
#define CHUNKS (KCFG_SIZE / CHUNK)

enum { PEND_NONE = 0, PEND_SAVE, PEND_ERASE, PEND_REBOOT };

static uint8_t staging[KCFG_SIZE] __attribute__((aligned(4)));
static uint8_t staged_mask;
static uint8_t read_idx;
static uint8_t last_cmd;
static uint8_t status;
static uint8_t pending;
static struct kishi_config boot_cfg;   /* what USB enumerated with */

void live_init(void)
{
	boot_cfg = kcfg;
	staged_mask = 0;
	read_idx = 0;
	last_cmd = 0;
	status = LS_OK;
	pending = PEND_NONE;
}

/* The fields only consumed at enumeration time: poll interval, VID/PID/bcdDevice, and the three strings. */
static int identity_differs(void)
{
	return boot_cfg.poll_ms != kcfg.poll_ms ||
	       memcmp(&boot_cfg.vid, &kcfg.vid, offsetof(struct kishi_config, reserved2) - offsetof(struct kishi_config, vid)) != 0 ||
	       memcmp(boot_cfg.manufacturer, kcfg.manufacturer,
	              offsetof(struct kishi_config, reserved3) - offsetof(struct kishi_config, manufacturer)) != 0;
}

void live_set_report(const uint8_t *d, unsigned len)
{
	uint8_t cmd, idx;

	if (len < 4 || d[0] != LIVE_REPORT_ID || d[1] != LIVE_SIGNATURE) {
		return;   /* not ours: ignore silently */
	}
	cmd = d[2];
	idx = d[3];
	last_cmd = cmd;
	status = LS_OK;

	switch (cmd) {
	case LC_STAGE:
		if (idx >= CHUNKS || len < 4u + CHUNK) {
			status = LS_BAD_CMD;
			break;
		}
		memcpy(&staging[idx * CHUNK], &d[4], CHUNK);
		staged_mask |= (uint8_t)(1u << idx);
		break;
	case LC_APPLY:
		if (staged_mask != 0xFF || !kcfg_block_valid((const struct kishi_config *)staging)) {
			status = LS_BAD_BLOCK;
		} else {
			memcpy(&kcfg, staging, sizeof(kcfg));
			kcfg_finalize();
			hw_apply_config();
		}
		staged_mask = 0;
		break;
	case LC_READSEL:
		if (idx >= CHUNKS) {
			status = LS_BAD_CMD;
		} else {
			read_idx = idx;
		}
		break;
	case LC_SAVE:
		pending = PEND_SAVE;
		break;
	case LC_ERASE:
		pending = PEND_ERASE;
		break;
	case LC_REBOOT:
		pending = PEND_REBOOT;
		break;
	default:
		status = LS_BAD_CMD;
		break;
	}
}

void live_get_report(uint8_t *out)
{
	uint8_t flags = 0;
	unsigned i;

	memset(out, 0, LIVE_REPORT_LEN);
	out[0] = LIVE_REPORT_ID;
	out[1] = LIVE_PROTOCOL;
	out[2] = status;
	out[3] = last_cmd;
	for (i = 0; i < 4; i++) {
		out[4 + i] = (uint8_t)(kcfg_active_crc >> (8 * i));
		out[8 + i] = (uint8_t)(kcfg_persisted_crc >> (8 * i));
	}
	if (kcfg_active_crc != kcfg_persisted_crc) {
		flags |= 1;
	}
	if (identity_differs()) {
		flags |= 2;
	}
	if (kcfg_from_saved) {
		flags |= 4;
	}
	if (pending != PEND_NONE) {
		flags |= 8;
	}
	out[12] = flags;
	out[13] = read_idx;
	memcpy(&out[16], (const uint8_t *)&kcfg + read_idx * CHUNK, CHUNK);
}

void live_service(void)
{
	uint8_t what = pending;

	if (what == PEND_NONE) {
		return;
	}
	if (what == PEND_SAVE) {
		struct kcfg_saved rec;

		memcpy(&rec.cfg, &kcfg, sizeof(rec.cfg));
		rec.image_id = kcfg_image_id;
		rec.commit = KCFG_SAVED_COMMIT;
		if (hw_flash_save(&rec) == 0) {
			kcfg_persisted_crc = kcfg_active_crc;
			kcfg_from_saved = 1;
			status = LS_OK;
		} else {
			status = LS_FLASH;
		}
	} else if (what == PEND_ERASE) {
		if (hw_flash_erase() == 0) {
			kcfg_persisted_crc = kcfg_fallback_crc;
			kcfg_from_saved = 0;
			status = LS_OK;
		} else {
			status = LS_FLASH;
		}
	}
	pending = PEND_NONE;
	if (what == PEND_REBOOT) {
		hw_reboot();
	}
}

uint8_t live_telemetry_flags(void)
{
	uint8_t f = TELE_LIVE;

	if (kcfg_from_saved) {
		f |= TELE_FROM_SAVED;
	}
	if (kcfg_active_crc != kcfg_persisted_crc) {
		f |= TELE_UNSAVED;
	}
	if (identity_differs()) {
		f |= TELE_IDENT_DIFF;
	}
	return f;
}
