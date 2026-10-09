/* USB flight recorder (see usblog_v2pro.h). */
#include "usblog_v2pro.h"

#include <string.h>

#include "diag.h"
#include "flash.h"
#include "live.h"
#include "lpc55.h"

#define SLOT0        0x0001E200u   /* past our image, inside Razer's span: the pages flash.c accepts */
#define SLOT1        0x0001E400u
#define WRITE_AT_MS  8000u
/* Last words of SRAM0 below our stack-free area: kept across resets, random after power-on. */
#define BOOTS_MAGIC  0xB0075EEDu
#define BOOTS_ADDR   0x2000FFF8u

struct usblog usblog;
static uint32_t next_slot;
static int written;

static uint32_t slot_seq(uint32_t addr)
{
	uint32_t w[4];

	if (flash_read_safe(addr, (uint8_t *)w, sizeof(w)) || w[0] != USBLOG_MAGIC) {
		return 0;
	}
	return w[1];
}

void usblog_start(void)
{
	volatile uint32_t *boots = (volatile uint32_t *)BOOTS_ADDR;
	uint32_t s0, s1;

	if (boots[0] != BOOTS_MAGIC || boots[1] > 250u) {
		boots[0] = BOOTS_MAGIC;
		boots[1] = 0;
	}
	boots[1]++;
	memset(&usblog, 0, sizeof(usblog));
	usblog.magic = USBLOG_MAGIC;
	usblog.boots = (uint8_t)boots[1];
	s0 = slot_seq(SLOT0);
	s1 = slot_seq(SLOT1);
	usblog.seq = (s0 > s1 ? s0 : s1) + 1u;
	next_slot = s0 > s1 ? SLOT1 : SLOT0;   /* overwrite the older one */
}

void usblog_poll(uint32_t ms)
{
	uint8_t page[FLASH_PAGE_SIZE];

	if (written || ms < WRITE_AT_MS) {
		return;
	}
	written = 1;
	usblog.usb_irqs = diag.usb_irqs;
	memset(page, 0xFF, sizeof(page));
	memcpy(page, &usblog, sizeof(usblog));
	if (flash_erase_page(next_slot) == 0) {
		(void)flash_program_page(next_slot, page);
	}
}

void usblog_get_report(uint8_t id)
{
	if (usblog.n_get < sizeof(usblog.get_ids)) {
		usblog.get_ids[usblog.n_get] = id;
	}
	if (usblog.n_get < 255u) {
		usblog.n_get++;
	}
}

void usblog_set_report(uint8_t id)
{
	if (usblog.n_set < sizeof(usblog.set_ids)) {
		usblog.set_ids[usblog.n_set] = id;
	}
	if (usblog.n_set < 255u) {
		usblog.n_set++;
	}
}

/*
 * KishiDS live READSEL 8..11: slot 0 (two 32-byte chunks), then slot 1, read through the flash controller.
 * 12..15: where the LPC55's factory UUID may be (NMPA + 0x70 on 640 KB and 256 KB parts), to find which one this
 * chip has; the controller answers an error (0xEE bytes) instead of faulting when the address is not there.
 */
void hw_board_chunk(unsigned n, uint8_t out[32])
{
	static const uint32_t probe[4] = {0x0009FC60u, 0x0003FC60u, 0x0009FC00u, 0x0003FC00u};
	uint32_t addr = n >= 4u ? probe[n - 4u] : (n < 2u ? SLOT0 : SLOT1) + 32u * (n & 1u);

	if (flash_read_safe(addr, out, 32)) {
		memset(out, 0xEE, 32);
	}
}
