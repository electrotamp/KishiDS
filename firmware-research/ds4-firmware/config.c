#include <string.h>

#include "config.h"

/*
 * The block KishiDS patches.  It sits in the image's read-only data and is found by its
 * 12-byte marker (magic + version + size).  The bare magic may also appear as a compiler
 * literal in the code, which is harmless because the marker is what is searched for.
 *
 * It is volatile on purpose: the compiler must not constant-fold the initialiser into the
 * code, or a patched image would be silently ignored.  It is always read byte-wise.
 */
__attribute__((used)) const volatile struct kishi_config kcfg_image = KCFG_DEFAULTS;

static const struct kishi_config kcfg_defaults = { KCFG_DEFAULTS_BODY };

struct kishi_config kcfg;
uint8_t kcfg_from_image;
uint8_t kcfg_from_saved;
uint32_t kcfg_active_crc;
uint32_t kcfg_image_id;
uint32_t kcfg_persisted_crc;
uint32_t kcfg_fallback_crc;

#ifdef KCFG_HOST_TEST
const uint8_t *kcfg_test_saved;
#define SAVED_PAGE (kcfg_test_saved)
#else
#define SAVED_PAGE ((const uint8_t *)KCFG_SAVED_ADDR)
#endif

uint32_t kcfg_crc32(const uint8_t *data, size_t len)
{
	uint32_t crc = 0xFFFFFFFFu;
	size_t i;
	unsigned b;

	for (i = 0; i < len; i++) {
		crc ^= data[i];
		for (b = 0; b < 8; b++) {
			crc = (crc >> 1) ^ (0xEDB88320u & (0u - (crc & 1u)));
		}
	}
	return ~crc;
}

static int magic_ok(const struct kishi_config *c)
{
	uint32_t w0, w1;

	memcpy(&w0, &c->magic[0], 4);
	memcpy(&w1, &c->magic[4], 4);
	return w0 == 0x4853494Bu && w1 == 0x47464349u;   /* "KISH" "ICFG" */
}

int kcfg_block_valid(const struct kishi_config *c)
{
	if (!magic_ok(c) || c->version != KCFG_VERSION || c->size != KCFG_SIZE) {
		return 0;
	}
	return c->crc32 == kcfg_crc32((const uint8_t *)c + 16, KCFG_SIZE - 16);
}

static void read_image_block(struct kishi_config *dst)
{
	const volatile uint8_t *s = (const volatile uint8_t *)&kcfg_image;
	uint8_t *d = (uint8_t *)dst;
	size_t i;

	for (i = 0; i < sizeof(*dst); i++) {
		d[i] = s[i];
	}
}

/* Clamp, terminate strings and stamp the CRC into *c; returns it. */
static uint32_t finalize_block(struct kishi_config *c)
{
	kcfg_clamp(c);
	c->manufacturer[sizeof(c->manufacturer) - 1] = 0;
	c->product[sizeof(c->product) - 1] = 0;
	c->serial[sizeof(c->serial) - 1] = 0;
	c->crc32 = kcfg_crc32((const uint8_t *)c + 16, KCFG_SIZE - 16);
	return c->crc32;
}

void kcfg_resolve_serial(char out[32], const uint8_t factory[KCFG_FACTORY_SERIAL_LEN], const uint32_t uid[3])
{
	static const char hex[] = "0123456789ABCDEF";
	size_t n = 0, i;

	memset(out, 0, 32);
	while (n < KCFG_FACTORY_SERIAL_LEN && factory[n] >= 0x21 && factory[n] <= 0x7E) {
		n++;
	}
	if (n >= 1 && n < KCFG_FACTORY_SERIAL_LEN && factory[n] == 0) {
		memcpy(out, factory, n);
		return;
	}
	for (i = 0; i < 24; i++) {
		out[i] = hex[(uid[i / 8] >> (28 - 4 * (i % 8))) & 0xF];
	}
}

void kcfg_finalize(void)
{
	kcfg_active_crc = finalize_block(&kcfg);
}

void kcfg_load(void)
{
	struct kishi_config image;
	struct kcfg_saved saved;
	struct kishi_config fallback;

	read_image_block(&image);
	kcfg_image_id = kcfg_crc32((const uint8_t *)&image + 16, KCFG_SIZE - 16);

	if (kcfg_block_valid(&image)) {
		fallback = image;
		kcfg_from_image = 1;
	} else {
		memcpy(&fallback, &kcfg_defaults, sizeof(fallback));
		kcfg_from_image = 0;
	}
	kcfg = fallback;
	kcfg_fallback_crc = finalize_block(&fallback);

	kcfg_from_saved = 0;
	if (SAVED_PAGE != NULL) {
		memcpy(&saved, SAVED_PAGE, sizeof(saved));
		if (saved.commit == KCFG_SAVED_COMMIT && saved.image_id == kcfg_image_id &&
		    kcfg_block_valid(&saved.cfg)) {
			kcfg = saved.cfg;
			kcfg_from_saved = 1;
			kcfg_from_image = 0;
		}
	}

	kcfg_finalize();
	kcfg_persisted_crc = kcfg_active_crc;
}
