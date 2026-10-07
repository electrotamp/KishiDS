/* Host tests for config persistence (saved record vs image) and the live-editing protocol. */
#ifndef KCFG_HOST_TEST
#define KCFG_HOST_TEST 1
#endif
#include <stdio.h>
#include <string.h>

#include "../live.h"

static int checks, failures;

#define CHECK(cond, ...) do { \
	checks++; \
	if (!(cond)) { failures++; printf("FAIL %s:%d: ", __FILE__, __LINE__); printf(__VA_ARGS__); printf("\n"); } \
} while (0)

/* ---- stubs for the hardware side ---- */
static uint8_t fake_flash[sizeof(struct kcfg_saved)];
static int flash_saves, flash_erases, reboots, applies, flash_fail;

void hw_apply_config(void) { applies++; }
int hw_flash_save(const struct kcfg_saved *r)
{
	if (flash_fail) return -1;
	flash_saves++;
	memcpy(fake_flash, r, sizeof(fake_flash));
	return 0;
}
int hw_flash_erase(void)
{
	if (flash_fail) return -1;
	flash_erases++;
	memset(fake_flash, 0xFF, sizeof(fake_flash));
	return 0;
}
void hw_reboot(void) { reboots++; }

/* The image block symbol exists in config.c (defaults on the host, magic + valid? no: crc 0). */
extern const volatile struct kishi_config kcfg_image;

static uint32_t rd32(const uint8_t *p) { return p[0] | (p[1] << 8) | (p[2] << 16) | ((uint32_t)p[3] << 24); }

static struct kishi_config block_with(uint8_t led_mode, uint8_t brightness)
{
	struct kishi_config c = KCFG_DEFAULTS;

	c.led_mode = led_mode;
	c.led_brightness = brightness;
	c.crc32 = kcfg_crc32((const uint8_t *)&c + 16, KCFG_SIZE - 16);
	return c;
}

static void send(uint8_t cmd, uint8_t idx, const uint8_t *data32)
{
	uint8_t pkt[64] = {LIVE_REPORT_ID, LIVE_SIGNATURE, cmd, idx};

	if (data32) memcpy(&pkt[4], data32, 32);
	live_set_report(pkt, sizeof(pkt));
}

static void stage_all(const struct kishi_config *c)
{
	for (uint8_t i = 0; i < 8; i++) send(LC_STAGE, i, (const uint8_t *)c + 32 * i);
}

static void boot(void)
{
	kcfg_load();
	live_init();
}

static void test_boot_sources(void)
{
	/* The image block on the host carries crc32 = 0 from KCFG_DEFAULTS, so it is invalid: defaults are used. */
	kcfg_test_saved = NULL;
	boot();
	CHECK(!kcfg_from_image && !kcfg_from_saved, "no image, no saved: defaults");
	CHECK(kcfg_active_crc == kcfg_persisted_crc && kcfg_persisted_crc == kcfg_fallback_crc, "persisted == fallback at boot");
	CHECK(kcfg.crc32 == kcfg_active_crc, "kcfg.crc32 stamped");

	/* A valid saved record with the right image_id wins. */
	struct kcfg_saved rec;
	rec.cfg = block_with(2, 77);
	rec.image_id = kcfg_image_id;
	rec.commit = KCFG_SAVED_COMMIT;
	kcfg_test_saved = (const uint8_t *)&rec;
	boot();
	CHECK(kcfg_from_saved && kcfg.led_mode == 2 && kcfg.led_brightness == 77, "saved record loaded");
	CHECK(kcfg_persisted_crc == kcfg_active_crc && kcfg_fallback_crc != kcfg_active_crc, "persisted is the saved crc, fallback differs");

	/* Stale (image_id mismatch), uncommitted, or corrupted records are ignored. */
	struct kcfg_saved bad = rec;
	bad.image_id ^= 1;
	kcfg_test_saved = (const uint8_t *)&bad;
	boot();
	CHECK(!kcfg_from_saved && kcfg.led_mode == 1, "stale image_id ignored");
	bad = rec; bad.commit = 0xFFFFFFFFu;
	boot();
	CHECK(!kcfg_from_saved, "uncommitted ignored");
	bad = rec; bad.cfg.led_mode = 3;   /* CRC no longer matches */
	boot();
	CHECK(!kcfg_from_saved && kcfg.led_mode == 1, "corrupted ignored");
	memset(&bad, 0xFF, sizeof(bad));
	boot();
	CHECK(!kcfg_from_saved, "erased page ignored");
	kcfg_test_saved = NULL;
}

static void test_protocol(void)
{
	uint8_t rep[LIVE_REPORT_LEN];
	struct kishi_config c = block_with(2, 100);

	memset(fake_flash, 0xFF, sizeof(fake_flash));
	kcfg_test_saved = (const uint8_t *)fake_flash;
	boot();
	uint32_t base_crc = kcfg_active_crc;

	/* Telemetry flags before anything happens. */
	CHECK((live_telemetry_flags() & TELE_LIVE) && !(live_telemetry_flags() & (TELE_UNSAVED | TELE_FROM_SAVED | TELE_IDENT_DIFF)), "clean flags");

	/* Foreign / malformed writes are ignored. */
	uint8_t junk[64] = {LIVE_REPORT_ID, 0x00, LC_APPLY, 0};
	live_set_report(junk, sizeof(junk));
	live_get_report(rep);
	CHECK(rep[3] == 0 && rep[2] == LS_OK, "missing signature ignored");
	live_set_report(junk, 2);

	/* APPLY without staging fails and changes nothing. */
	send(LC_APPLY, 0, NULL);
	live_get_report(rep);
	CHECK(rep[2] == LS_BAD_BLOCK && rep[3] == LC_APPLY && kcfg_active_crc == base_crc, "apply without stage rejected");

	/* Partial staging is rejected. */
	for (uint8_t i = 0; i < 7; i++) send(LC_STAGE, i, (const uint8_t *)&c + 32 * i);
	send(LC_APPLY, 0, NULL);
	live_get_report(rep);
	CHECK(rep[2] == LS_BAD_BLOCK && kcfg_active_crc == base_crc, "partial stage rejected");

	/* Bad chunk index / short data. */
	send(LC_STAGE, 8, (const uint8_t *)&c);
	live_get_report(rep);
	CHECK(rep[2] == LS_BAD_CMD, "chunk 8 rejected");
	uint8_t shortpkt[10] = {LIVE_REPORT_ID, LIVE_SIGNATURE, LC_STAGE, 0};
	live_set_report(shortpkt, sizeof(shortpkt));
	live_get_report(rep);
	CHECK(rep[2] == LS_BAD_CMD, "short stage rejected");

	/* A corrupted block is rejected even if fully staged. */
	struct kishi_config corrupt = c;
	corrupt.led_brightness ^= 0x55;
	stage_all(&corrupt);
	send(LC_APPLY, 0, NULL);
	live_get_report(rep);
	CHECK(rep[2] == LS_BAD_BLOCK && kcfg.led_mode == 1, "bad crc rejected");

	/* Good block applies live, immediately, not saved. */
	applies = 0;
	stage_all(&c);
	send(LC_APPLY, 0, NULL);
	live_get_report(rep);
	CHECK(rep[2] == LS_OK && kcfg.led_mode == 2 && kcfg.led_brightness == 100 && applies == 1, "apply ok");
	CHECK(rd32(&rep[4]) == c.crc32 && rd32(&rep[4]) == kcfg_active_crc, "active crc reported == block crc");
	CHECK(rd32(&rep[8]) == base_crc && (rep[12] & 1), "unsaved flag set, persisted unchanged");
	CHECK(live_telemetry_flags() & TELE_UNSAVED, "telemetry unsaved");
	CHECK(flash_saves == 0, "nothing written to flash yet");

	/* Staging is consumed: a second APPLY without restaging fails. */
	send(LC_APPLY, 0, NULL);
	live_get_report(rep);
	CHECK(rep[2] == LS_BAD_BLOCK, "stage consumed");

	/* Out-of-range values are clamped on apply and the clamped CRC is reported. */
	struct kishi_config wild = block_with(1, 255);
	wild.left_dead = 200;
	wild.crc32 = kcfg_crc32((const uint8_t *)&wild + 16, KCFG_SIZE - 16);
	stage_all(&wild);
	send(LC_APPLY, 0, NULL);
	CHECK(kcfg.left_dead == 50 && kcfg.crc32 == kcfg_active_crc && kcfg_block_valid(&kcfg), "clamped on apply, crc restamped");
	stage_all(&c);
	send(LC_APPLY, 0, NULL);

	/* Read back all 8 chunks == active block. */
	uint8_t readback[256];
	for (uint8_t i = 0; i < 8; i++) {
		send(LC_READSEL, i, NULL);
		live_get_report(rep);
		CHECK(rep[13] == i, "read idx %d", i);
		memcpy(&readback[32 * i], &rep[16], 32);
	}
	CHECK(memcmp(readback, &c, 256) == 0, "readback equals applied block");
	send(LC_READSEL, 9, NULL);
	live_get_report(rep);
	CHECK(rep[2] == LS_BAD_CMD && rep[13] == 7, "bad readsel rejected, index kept");

	/* SAVE is deferred, then persisted. */
	send(LC_SAVE, 0, NULL);
	live_get_report(rep);
	CHECK((rep[12] & 8) && flash_saves == 0, "save pending until serviced");
	live_service();
	live_get_report(rep);
	CHECK(flash_saves == 1 && !(rep[12] & 8) && !(rep[12] & 1) && (rep[12] & 4) && rd32(&rep[8]) == c.crc32 && rep[2] == LS_OK, "saved");
	struct kcfg_saved *rec = (struct kcfg_saved *)fake_flash;
	CHECK(rec->commit == KCFG_SAVED_COMMIT && rec->image_id == kcfg_image_id && kcfg_block_valid(&rec->cfg), "flash record well-formed");

	/* "Power cycle": the saved record is what boots. */
	boot();
	CHECK(kcfg_from_saved && kcfg.led_mode == 2 && kcfg.led_brightness == 100, "reboots into saved config");

	/* ERASE returns to the fallback. */
	send(LC_ERASE, 0, NULL);
	live_service();
	live_get_report(rep);
	CHECK(flash_erases == 1 && rd32(&rep[8]) == kcfg_fallback_crc && (rep[12] & 1), "erase: persisted -> fallback, unsaved flag");
	boot();
	CHECK(!kcfg_from_saved && kcfg.led_mode == 1, "after erase boots fallback");

	/* Flash failure is reported and does not claim persistence. */
	send(LC_SAVE, 0, NULL);
	flash_fail = 1;
	live_service();
	live_get_report(rep);
	CHECK(rep[2] == LS_FLASH && rd32(&rep[8]) == kcfg_fallback_crc, "flash failure reported");
	flash_fail = 0;

	/* Reboot only after the deferred step. */
	send(LC_REBOOT, 0, NULL);
	CHECK(reboots == 0, "reboot deferred");
	live_service();
	CHECK(reboots == 1, "reboot executed");

	/* Unknown command. */
	send(99, 0, NULL);
	live_get_report(rep);
	CHECK(rep[2] == LS_BAD_CMD, "unknown cmd");
}

static void test_identity_flag(void)
{
	kcfg_test_saved = NULL;
	boot();
	struct kishi_config c = block_with(1, 255);
	uint8_t rep[LIVE_REPORT_LEN];

	/* Non-identity change: no flag. */
	c.left_dead = 20;
	c.crc32 = kcfg_crc32((const uint8_t *)&c + 16, KCFG_SIZE - 16);
	stage_all(&c); send(LC_APPLY, 0, NULL);
	live_get_report(rep);
	CHECK(!(rep[12] & 2), "non-identity change does not flag identity");

	/* Each identity field flags it. */
	struct kishi_config v[5];
	for (int i = 0; i < 5; i++) v[i] = c;
	v[0].poll_ms = 2; v[1].product[1] = 'Y'; v[2].product[0] = 'Z'; v[3].product[0] = 'X'; v[4].product[7] = 'Q';
	for (int i = 0; i < 5; i++) {
		v[i].crc32 = kcfg_crc32((const uint8_t *)&v[i] + 16, KCFG_SIZE - 16);
		boot();
		stage_all(&v[i]); send(LC_APPLY, 0, NULL);
		live_get_report(rep);
		CHECK((rep[12] & 2) && (live_telemetry_flags() & TELE_IDENT_DIFF), "identity change %d flagged", i);
	}

	/* VID/PID/bcdDevice/manufacturer are locked: an applied block cannot change them. */
	{
		struct kishi_config l = c;
		l.vid = 0x1234; l.pid = 0x4321; l.bcd_device = 0x0999; l.manufacturer[0] = 'Q'; l.serial[0] = 'Z';
		l.crc32 = kcfg_crc32((const uint8_t *)&l + 16, KCFG_SIZE - 16);
		boot();
		stage_all(&l); send(LC_APPLY, 0, NULL);
		live_get_report(rep);
		CHECK(kcfg.vid == 0x054C && kcfg.pid == 0x05C4 && kcfg.bcd_device == 0x0100, "locked VID/PID/bcd forced to DS4 values");
		CHECK(strcmp(kcfg.manufacturer, "ElectroTamp KishiDS") == 0, "locked manufacturer forced");
		CHECK(kcfg.serial[0] == 0, "locked serial forced empty (resolved to the factory serial at boot)");
		CHECK(!(rep[12] & 2), "locked fields never raise the identity flag");
	}
}

static void test_serial(void)
{
	static const uint32_t uid[3] = { 0x12345678, 0x9ABCDEF0, 0x0F1E2D3C };
	const char *uid_hex = "123456789ABCDEF00F1E2D3C";
	uint8_t f[KCFG_FACTORY_SERIAL_LEN];
	char out[32];

	/* The real factory field read from a unit: 15 characters, then NUL. */
	memcpy(f, "SAMPLE000000001\0", 16);
	kcfg_resolve_serial(out, f, uid);
	CHECK(strcmp(out, "SAMPLE000000001") == 0, "factory serial is used verbatim");
	CHECK(out[16] == 0 && out[31] == 0, "serial buffer is zero padded");

	memcpy(f, "SN1\0\xFF\xFF\xFF\xFF\xFF\xFF\xFF\xFF\xFF\xFF\xFF\xFF", 16);
	kcfg_resolve_serial(out, f, uid);
	CHECK(strcmp(out, "SN1") == 0, "a shorter serial is accepted");

	memset(f, 0xFF, sizeof(f));
	kcfg_resolve_serial(out, f, uid);
	CHECK(strcmp(out, uid_hex) == 0, "erased factory field falls back to the chip ID");

	memset(f, 0, sizeof(f));
	kcfg_resolve_serial(out, f, uid);
	CHECK(strcmp(out, uid_hex) == 0, "empty factory field falls back to the chip ID");

	memcpy(f, "0123456789ABCDEF", 16);
	kcfg_resolve_serial(out, f, uid);
	CHECK(strcmp(out, uid_hex) == 0, "unterminated factory field falls back to the chip ID");

	memcpy(f, "AB\x01" "CD\0\0\0\0\0\0\0\0\0\0\0", 16);
	kcfg_resolve_serial(out, f, uid);
	CHECK(strcmp(out, uid_hex) == 0, "non-printable factory field falls back to the chip ID");
}

int main(void)
{
	test_serial();
	test_boot_sources();
	test_protocol();
	test_identity_flag();
	printf("%d checks, %d failures\n", checks, failures);
	return failures ? 1 : 0;
}
