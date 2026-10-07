/*
 * Flash persistence and reset for live editing (hardware side of live.h).
 *
 * The settings record lives in its own 2 KiB page (KCFG_SAVED_ADDR).  Erasing a page stalls the CPU
 * for tens of milliseconds, so this is only ever called from the main loop, never from a USB handler.
 * The record's commit word is programmed last: a power cut mid-write leaves a record that fails
 * validation and the controller simply boots from the image block.
 */
#include <string.h>

#include <libopencm3/cm3/scb.h>
#include <libopencm3/stm32/flash.h>
#include <libopencm3/stm32/rcc.h>

#include "config.h"
#include "live.h"

#define USB_BCDR_REG (*(volatile uint32_t *)0x40005C58)

int hw_flash_save(const struct kcfg_saved *rec)
{
	uint16_t half[sizeof(*rec) / 2];
	const uint8_t *page = (const uint8_t *)KCFG_SAVED_ADDR;
	unsigned i;

	memcpy(half, rec, sizeof(half));
	rcc_osc_on(RCC_HSI);                 /* the flash controller needs the HSI oscillator running */
	rcc_wait_for_osc_ready(RCC_HSI);
	flash_unlock();
	flash_erase_page(KCFG_SAVED_ADDR);
	for (i = 0; i < sizeof(half) / 2; i++) {
		flash_program_half_word(KCFG_SAVED_ADDR + 2u * i, half[i]);
	}
	flash_lock();
	return memcmp(page, rec, sizeof(*rec)) == 0 ? 0 : -1;
}

int hw_flash_erase(void)
{
	const uint8_t *page = (const uint8_t *)KCFG_SAVED_ADDR;
	unsigned i;

	rcc_osc_on(RCC_HSI);
	rcc_wait_for_osc_ready(RCC_HSI);
	flash_unlock();
	flash_erase_page(KCFG_SAVED_ADDR);
	flash_lock();
	for (i = 0; i < sizeof(struct kcfg_saved); i++) {
		if (page[i] != 0xFF) {
			return -1;
		}
	}
	return 0;
}

void hw_reboot(void)
{
	volatile uint32_t i;

	USB_BCDR_REG = 0;                    /* drop the D+ pull-up so the host sees a disconnect */
	for (i = 0; i < 2000000; i++) {      /* ~100 ms */
	}
	scb_reset_system();
}
