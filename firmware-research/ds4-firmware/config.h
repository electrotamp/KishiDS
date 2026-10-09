/*
 * Runtime configuration.  The image carries a 256-byte block (see config_layout.h,
 * generated from tools/gen_config.py) that KishiDS patches before flashing.
 * It is validated at boot (magic, version, size, CRC-32) and every ranged field is
 * clamped; if anything is wrong the built-in defaults are used instead, so a bad
 * edit can never leave the controller unusable.
 *
 * Live editing adds a second, optional source: a "saved" record in flash page
 * KCFG_SAVED_ADDR written over USB (see live.h).  It only wins at boot while its
 * image_id matches the image block it was saved against, so flashing a new image
 * with DFU always supersedes stale saved settings.
 */
#ifndef CONFIG_H
#define CONFIG_H

#include <stddef.h>
#include <stdint.h>

#include "config_layout.h"

/* Flash page (2 KiB) holding the saved record.  0x0800E000 is the DIAG log, 0x0800F800 stock calibration.
 * Other boards (../v2pro-firmware) define their own address on the command line. */
#ifndef KCFG_SAVED_ADDR
#define KCFG_SAVED_ADDR   0x0800F000u
#endif
#define KCFG_SAVED_COMMIT 0x5AFE5AFEu

/* 264 bytes; the commit word is programmed last so a power cut mid-write leaves an invalid record. */
struct __attribute__((packed)) kcfg_saved {
	struct kishi_config cfg;
	uint32_t image_id;   /* kcfg_image_id of the image the record was saved against */
	uint32_t commit;     /* KCFG_SAVED_COMMIT */
};

/* The active configuration (valid, clamped, strings NUL-terminated, crc32 field = kcfg_active_crc). */
extern struct kishi_config kcfg;

/* 1 if the block in the image was valid and in use, 0 otherwise (defaults, or the saved record). */
extern uint8_t kcfg_from_image;

/* 1 if the saved flash record was valid, matched the image and is in use. */
extern uint8_t kcfg_from_saved;

/* CRC-32 (ISO-HDLC, as zlib/PNG) of the active block's bytes 16..255, as the tool computes it. */
extern uint32_t kcfg_active_crc;

/* Identity of the image block (CRC-32 of its bytes 16..255 as stored, valid or not). */
extern uint32_t kcfg_image_id;

/* CRC of the configuration that will be in use after the next reset. */
extern uint32_t kcfg_persisted_crc;

/* CRC of what the next boot falls back to if the saved record is erased (image block, or the defaults). */
extern uint32_t kcfg_fallback_crc;

uint32_t kcfg_crc32(const uint8_t *data, size_t len);

/* Validate the image block / saved record and copy the winner (or the defaults) into kcfg. */
void kcfg_load(void);

/* Re-clamp kcfg after it was replaced, terminate its strings, and refresh crc32 / kcfg_active_crc. */
void kcfg_finalize(void);

/*
 * The USB serial number string (out: 32 bytes, NUL-terminated).  The stock firmware keeps the per-unit serial printed
 * on the Kishi's sticker as plain text in a 16-byte factory field inside its calibration page
 * (KCFG_FACTORY_SERIAL_ADDR); we reuse it so the controller keeps its own number.  If that field doesn't hold 1..15
 * printable characters plus a NUL, the 96-bit chip ID (3 words at KCFG_UID_ADDR) is used as 24 hex digits instead.
 */
#define KCFG_FACTORY_SERIAL_ADDR 0x0800F840u
#define KCFG_FACTORY_SERIAL_LEN  16u
#define KCFG_UID_ADDR            0x1FFFF7ACu
void kcfg_resolve_serial(char out[32], const uint8_t factory[KCFG_FACTORY_SERIAL_LEN], const uint32_t uid[3]);

/* Validate a candidate block; returns 1 if magic/version/size/CRC are all correct. */
int kcfg_block_valid(const struct kishi_config *c);

#ifdef KCFG_SAVED_VIA_BOARD
/*
 * Boards whose saved page cannot simply be read (LPC55: an erased or half-written page faults on a bus read) copy the
 * record out with a read that cannot fault and point this at the copy before kcfg_load(), or leave it NULL.
 */
extern const uint8_t *kcfg_board_saved;
#endif

#ifdef KCFG_HOST_TEST
/* Host tests point this at a fake flash page; null means "no saved record". */
extern const uint8_t *kcfg_test_saved;
#endif

#endif
