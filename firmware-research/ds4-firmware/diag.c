/*
 * Diagnostics, compiled only with DIAG=1.
 *
 * Black-box boot log in spare flash (page 0x0800E000; the stock calibration page
 * 0x0800F800 is never touched).  One 56-byte slot (28 half-words) per boot; each
 * milestone programs its own half-word once.  It survives DFU re-flashes, so after a
 * failed boot, reflash a DIAG build and read the two newest slots through feature
 * reports 0xF1 / 0xB0 (newest first) with tools/diag_log.py.  The register snapshot
 * taken at entry is on 0xF0.  (0xAB is the app telemetry report.)  The blue LED (PB4)
 * blinks ~3 Hz as a liveness heartbeat.
 */

#include <stdint.h>

#include <libopencm3/stm32/rcc.h>
#include <libopencm3/stm32/flash.h>

#include "diag.h"
#include "kishi_io.h"
#include "led.h"

#define REG32(a) (*(volatile uint32_t *)(a))
#define USB_CNTR_REG  REG32(0x40005C40)
#define USB_ISTR_REG  REG32(0x40005C44)
#define USB_DADDR_REG REG32(0x40005C4C)

#define LOG_PAGE   0x0800E000u
#define LOG_HW     28u
#define LOG_SLOTS  (2048u / (LOG_HW * 2u))

static volatile uint16_t *log_slot;
static uint32_t snap_entry[15];
static uint8_t report_buf[64];
static unsigned beat, loops;
static int blink;

static volatile uint16_t *slot_at(unsigned n)
{
	return (volatile uint16_t *)(LOG_PAGE + n * LOG_HW * 2u);
}

static const volatile uint16_t *log_recent(unsigned n)
{
	int last = -1;
	unsigned i;

	for (i = 0; i < LOG_SLOTS; i++) {
		if (slot_at(i)[0] != 0xFFFF) {
			last = (int)i;
		}
	}
	return (last < 0 || (int)n > last) ? 0 : slot_at((unsigned)last - n);
}

void diag_mark(unsigned idx, uint16_t v)
{
	if (!log_slot || log_slot[idx] != 0xFFFF || v == 0xFFFF) {
		return;
	}
	flash_unlock();
	flash_program_half_word((uint32_t)&log_slot[idx], v);
	flash_lock();
}

static void snapshot(uint32_t *o)
{
	o[0] = RCC_CR;
	o[1] = RCC_CFGR;
	o[2] = RCC_CFGR2;
	o[3] = RCC_CFGR3;
	o[4] = RCC_APB1ENR;
	o[5] = RCC_AHBENR;
	o[6] = REG32(0x40006C00);   /* CRS_CR */
	o[7] = USB_CNTR_REG;
	o[8] = REG32(0x40005C58);   /* USB_BCDR */
	o[9] = REG32(0xE000E100);   /* NVIC_ISER */
	o[10] = REG32(0x40010000);  /* SYSCFG_CFGR1 */
	o[11] = REG32(0x40022000);  /* FLASH_ACR */
	o[12] = REG32(0x48000000);  /* GPIOA_MODER */
	o[13] = REG32(0x40021034);  /* RCC_CR2 */
	o[14] = RCC_CSR;
}

void diag_boot(void)
{
	unsigned i;

	snapshot(snap_entry);
	for (i = 0; i < LOG_SLOTS && !log_slot; i++) {
		if (slot_at(i)[0] == 0xFFFF) {
			log_slot = slot_at(i);
		}
	}
	if (!log_slot) {
		flash_unlock();
		flash_erase_page(LOG_PAGE);
		flash_lock();
		log_slot = slot_at(0);
	}
	diag_mark(DM_ENTRY, 0xB007);
	diag_mark(DM_RESET_CAUSE, (uint16_t)(RCC_CSR >> 16));   /* latest reset cause */
	RCC_CSR |= (1u << 24);                                  /* RMVF: next boot shows only its own */
}

void diag_after_clock(void)
{
	diag_mark(DM_CLOCK_CR, (uint16_t)(RCC_CR >> 16));
	diag_mark(DM_CLOCK_CR2, (uint16_t)(REG32(0x40021034) >> 16));
}

void diag_after_usb_setup(void)
{
	diag_mark(DM_USB_CNTR, (uint16_t)USB_CNTR_REG);
}

void diag_loop(void)
{
	if ((++beat & 0x3FF) == 0) {
		blink = !blink;
	}
	led_force(blink ? 255 : 0);
	loops++;
	if (loops == 2) {
		diag_mark(DM_LOOP2, 0x1002);
	} else if (loops == 10) {
		diag_mark(DM_LOOP10, 0x1010);
		diag_mark(DM_ISTR_LOOP10, (uint16_t)USB_ISTR_REG | 0x4000u);
	} else if (loops == 100) {
		diag_mark(DM_LOOP100, 0x1100);
	} else if (loops == 1000) {
		diag_mark(DM_LOOP1000, 0x1999);
	} else if (loops == 5000) {
		diag_mark(DM_LOOP_ALIVE, 0x600D);
	} else if (loops == 30000) {
		diag_mark(DM_ISTR_10S, (uint16_t)USB_ISTR_REG);
		diag_mark(DM_DADDR_10S, (uint16_t)USB_DADDR_REG);
		diag_mark(DM_CNTR_10S, (uint16_t)USB_CNTR_REG);
		diag_mark(DM_MARK_10S, 0xD00D);
	}
}

/* Raw ADC values and button mask in the (otherwise unused) gyro/accel bytes 13..26. */
void diag_fill_report(uint8_t *r, uint16_t buttons, const uint16_t *adc)
{
	unsigned i;

	for (i = 0; i < KISHI_ADC_COUNT; i++) {
		r[13 + 2 * i] = adc[i] & 0xFF;
		r[14 + 2 * i] = adc[i] >> 8;
	}
	r[25] = buttons & 0xFF;
	r[26] = buttons >> 8;
}

/*
 * Read-only memory dump.  SET_FEATURE 0xF2 [id][addr u32 LE] selects an address; the next GET_FEATURE 0xB0
 * then returns 63 bytes from there instead of the second boot-log slot.  Only flash, the factory UID/option-byte
 * area and RAM are allowed, so a bad address can't fault the core.  Used to find where the stock firmware keeps
 * the per-unit serial number (tools/diag_mem.py).
 */
#define MEM_CHUNK 63u
static uint32_t mem_addr;
static int mem_mode;

static int mem_ok(uint32_t a)
{
	return (a >= 0x08000000u && a + MEM_CHUNK <= 0x08010000u) ||
		(a >= 0x1FFFF7A0u && a + MEM_CHUNK <= 0x1FFFF820u) ||
		(a >= 0x1FFFD800u && a + MEM_CHUNK <= 0x1FFFF800u) ||
		(a >= 0x20000000u && a + MEM_CHUNK <= 0x20004000u);
}

void diag_set_report(const uint8_t *b, uint16_t len)
{
	if (len >= 5 && b[0] == 0xF2) {
		mem_addr = (uint32_t)b[1] | ((uint32_t)b[2] << 8) | ((uint32_t)b[3] << 16) | ((uint32_t)b[4] << 24);
		mem_mode = 1;
	}
}

int diag_feature_report(uint8_t id, uint8_t **buf, uint16_t *len)
{
	unsigned i, n;

	if (id == 0xB0 && mem_mode) {
		const volatile uint8_t *p = (const volatile uint8_t *)mem_addr;
		int ok = mem_ok(mem_addr);

		report_buf[0] = id;
		for (i = 0; i < MEM_CHUNK; i++) {
			report_buf[1 + i] = ok ? p[i] : 0xEE;
		}
		*buf = report_buf;
		*len = sizeof(report_buf);
		return 1;
	}

	switch (id) {
	case 0xF1: case 0xB0: {
		const volatile uint16_t *sl = log_recent(id == 0xF1 ? 0 : 1);

		for (i = 0; i < sizeof(report_buf); i++) {
			report_buf[i] = 0xFF;
		}
		report_buf[0] = id;
		for (i = 0; sl && i < LOG_HW; i++) {
			report_buf[1 + i * 2] = sl[i] & 0xFF;
			report_buf[2 + i * 2] = sl[i] >> 8;
		}
		break;
	}
	case 0xF0: {
		const uint8_t *src = (const uint8_t *)snap_entry;

		n = 15u * 4u;
		for (i = 0; i < sizeof(report_buf); i++) {
			report_buf[i] = 0;
		}
		report_buf[0] = id;
		for (i = 0; i < n; i++) {
			report_buf[1 + i] = src[i];
		}
		break;
	}
	default:
		return 0;
	}
	*buf = report_buf;
	*len = sizeof(report_buf);
	return 1;
}
