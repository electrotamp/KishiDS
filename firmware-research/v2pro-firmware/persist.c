/*
 * Settings persistence: the flash hooks live.c (shared with the V1) calls, plus the safe boot-time read.
 * (hw_apply_config, the hook that re-derives cached state, is in main.c next to the cached calibration.)
 *
 * The record lives in its own 512-byte page at KCFG_SAVED_ADDR (0x1E000), inside our image span, so a DFU flash
 * replaces it (as on the V1, where a new image supersedes saved settings).  Razer's own settings area is unknown and
 * never touched.
 *
 * LPC55 rule this file exists for: a plain bus read of a page that is erased-but-unprogrammed, or was cut off while
 * being programmed, faults the CPU (ECC).  A fault at boot would repeat on every boot (each one ending in Razer's
 * bootloader, see startup.c).  So the page is only ever read through the flash controller (flash_read_safe), which
 * reports such a page as an error; config.c then just sees "no saved record".
 *
 * On hardware (2026-10-09) a saved record survives restarts and power cycles once flash_power_init() has done Razer's
 * brown-out set-up; before that, erase/program reported DONE and changed nothing.  A visit to Razer's bootloader
 * clears the page (seen once, not investigated): harmless, since a new image replaces the settings anyway.
 */
#include <string.h>

#include "config.h"
#include "flash.h"
#include "live.h"
#include "lpc55.h"

/* Shipped in the image as programmed 0xFF, so a freshly flashed controller has a readable, empty record page. */
__attribute__((section(".kcfg_saved"), used, aligned(512)))
const uint8_t kcfg_saved_page[FLASH_PAGE_SIZE] = {[0 ... FLASH_PAGE_SIZE - 1] = 0xFF};

#define RECORD_READ_LEN ((sizeof(struct kcfg_saved) + 15u) & ~15u)

const uint8_t *kcfg_board_saved;
static uint8_t saved_copy[RECORD_READ_LEN];

void persist_load(void)
{
	flash_power_init();
	kcfg_board_saved = flash_read_safe(KCFG_SAVED_ADDR, saved_copy, RECORD_READ_LEN) == 0 ? saved_copy : NULL;
}

/* Erase, program, then read back through the controller and compare.  0 only if the page holds exactly `page`. */
static int write_page(const uint8_t page[FLASH_PAGE_SIZE])
{
	uint8_t check[FLASH_PAGE_SIZE];

	if (flash_erase_page(KCFG_SAVED_ADDR) || flash_program_page(KCFG_SAVED_ADDR, page)) {
		return -1;
	}
	if (flash_read_safe(KCFG_SAVED_ADDR, check, FLASH_PAGE_SIZE) || memcmp(check, page, FLASH_PAGE_SIZE)) {
		return -1;
	}
	return 0;
}

int hw_flash_save(const struct kcfg_saved *rec)
{
	uint8_t page[FLASH_PAGE_SIZE];

	memset(page, 0xFF, sizeof(page));
	memcpy(page, rec, sizeof(*rec));
	return write_page(page);
}

int hw_flash_erase(void)
{
	uint8_t page[FLASH_PAGE_SIZE];

	/* Programmed 0xFF rather than left erased: no record, and still readable by anything. */
	memset(page, 0xFF, sizeof(page));
	return write_page(page);
}

void hw_reboot(void)
{
	SCB_AIRCR = SCB_AIRCR_SYSRESET;
	for (;;) {
	}
}
