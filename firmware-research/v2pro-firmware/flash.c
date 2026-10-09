/*
 * Flash controller access, register for register the sequences of the flash driver in Razer's firmware 2.2 (see
 * lpc55.h).  Only pages inside our own image span are accepted: Razer's bootloader (below 0x8000) and anything past
 * Razer's own image end (where Razer may keep its settings) are refused.
 */
#include "flash.h"

#include "diag.h"
#include "lpc55.h"

#define FLASH_APP_END 0x0001EC00u   /* Razer's 2.2 image ends at 0x1EBFF; our image stays inside that span */

#define PMC_RESETCTRL      LPC55_REG32(PMC_BASE + 0x08u)
#define PMC_BODVBAT        LPC55_REG32(PMC_BASE + 0x30u)
#define RESETCTRL_BODVBAT  (1u << 1)
#define BODVBAT_RAZER      0x53u        /* trigger level and hysteresis, bits 6:0, as Razer's 0x1663c */

void flash_power_init(void)
{
	PMC_PDRUNCFGCLR0 = 1u << 9;          /* Razer's 0x165b8 / 0x165bc, in this order */
	PMC_PDRUNCFGCLR0 = 1u << 23;
	PMC_BODVBAT = (PMC_BODVBAT & ~0x7Fu) | BODVBAT_RAZER;
	PMC_RESETCTRL |= RESETCTRL_BODVBAT;
}

static int page_ok(uint32_t addr)
{
	return addr >= FLASH_APP_FIRST && addr + FLASH_PAGE_SIZE <= FLASH_APP_END && !(addr & (FLASH_PAGE_SIZE - 1u));
}

/*
 * One controller command and its wait, executed from RAM (.ramfunc, copied with .data by Reset_Handler) with interrupts off: while
 * the controller erases or programs, the flash array cannot serve instruction fetches, and on the first hardware
 * runs every attempt to write a page from code running in flash ended in a fault.  Everything this function touches
 * is RAM or a register; it calls nothing.
 *
 * DONE stays set until cleared, so it is cleared before every command (as Razer's driver does, 0xa776): otherwise the
 * wait would see the previous command's DONE and the next command would go to a busy controller.
 */
__attribute__((section(".ramfunc"), noinline, long_call))
static uint32_t run_from_ram(uint32_t cmd)
{
	uint32_t st;

	FLASH_INT_CLR_STATUS = 0xFu;
	FLASH_CMD = cmd;
	WAIT_UNTIL((st = FLASH_INT_STATUS) & FLASH_INT_DONE, DW_FLASH_CMD);
	return st;
}

static int run(uint32_t cmd)
{
	uint32_t st, primask;

	__asm volatile ("mrs %0, primask\n\tcpsid i" : "=r"(primask) :: "memory");
	st = run_from_ram(cmd);
	__asm volatile ("msr primask, %0" :: "r"(primask) : "memory");
	return (st & FLASH_INT_ERRORS) || !(st & FLASH_INT_DONE) ? -1 : 0;
}

int flash_erase_page(uint32_t addr)
{
	int r;

	if (!page_ok(addr)) {
		return -1;
	}
	FLASH_INT_CLR_STATUS = 0xFu;
	FLASH_STARTA = addr >> 4;
	FLASH_STOPA = addr >> 4;   /* last page of the range = this page */
	r = run(FLASH_CMD_ERASE_RANGE);
	SYSCON_FMCFLUSH = 1u;
	return r;
}

int flash_program_page(uint32_t addr, const uint8_t data[512])
{
	uint32_t off, w;
	unsigned i;
	int r;

	if (!page_ok(addr)) {
		return -1;
	}
	FLASH_INT_CLR_STATUS = 0xFu;
	for (off = 0; off < FLASH_PAGE_SIZE; off += 16u) {
		FLASH_STARTA = (addr + off) >> 4;
		for (i = 0; i < 4u; i++) {
			const uint8_t *p = data + off + 4u * i;

			w = (uint32_t)p[0] | ((uint32_t)p[1] << 8) | ((uint32_t)p[2] << 16) | ((uint32_t)p[3] << 24);
			FLASH_DATAW(i) = w;
		}
		if (run(FLASH_CMD_WRITE)) {
			SYSCON_FMCFLUSH = 1u;
			return -1;
		}
	}
	r = run(FLASH_CMD_PROGRAM);
	SYSCON_FMCFLUSH = 1u;
	return r;
}

int flash_read_safe(uint32_t addr, uint8_t *out, uint32_t len)
{
	uint32_t off, w;
	unsigned i;

	if ((addr | len) & 15u) {
		return -1;
	}
	for (off = 0; off < len; off += 16u) {
		FLASH_INT_CLR_STATUS = 0xFu;
		FLASH_STARTA = (addr + off) >> 4;
		FLASH_DATAW(0) = 0u;   /* normal read margins (Razer's default) */
		if (run(FLASH_CMD_READ_SINGLE_WORD)) {
			return -1;
		}
		for (i = 0; i < 4u; i++) {
			w = FLASH_DATAW(i);
			out[off + 4u * i + 0u] = (uint8_t)w;
			out[off + 4u * i + 1u] = (uint8_t)(w >> 8);
			out[off + 4u * i + 2u] = (uint8_t)(w >> 16);
			out[off + 4u * i + 3u] = (uint8_t)(w >> 24);
		}
	}
	return 0;
}
