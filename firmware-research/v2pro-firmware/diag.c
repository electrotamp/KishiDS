/* Start-up diagnostics (see diag.h). */
#include <string.h>

#include "bootloader.h"
#include "diag.h"
#include "flash.h"
#include "lpc55.h"

struct diag diag;
uint32_t diag_stamp[DS_COUNT];
volatile uint8_t diag_breadcrumb;

void diag_start_cycles(void)
{
#ifndef KCFG_HOST_TEST
	*(volatile uint32_t *)0xE000EDFCu |= 1u << 24;          /* DEMCR.TRCENA: DWT on */
	*(volatile uint32_t *)0xE0001FB0u = 0xC5ACCE55u;        /* DWT_LAR unlock, where implemented */
	*(volatile uint32_t *)0xE0001004u = 0;                  /* CYCCNT */
	*(volatile uint32_t *)0xE0001000u |= 1u;                /* DWT_CTRL.CYCCNTENA */
#endif
}

#ifdef V2PRO_DIAG

#define USBFSH_PORTMODE_ADDR 0x400A205Cu   /* only readable while the USB0 host-slave clock is on (else BusFault) */

/*
 * Where the record goes: the first page past our image, still holding Razer's leftover bytes (zeros) after a flash of
 * ours.  On hardware a write to the saved-settings page (0x1E000, shipped as 0xFF) reported success but read back as
 * 0xFF, which cannot tell "nothing written" from "erased, not programmed"; here the three outcomes all differ.
 */
#define DIAG_RECORD_ADDR 0x0001E200u

/*
 * Snapshot, in this order (tools/v2pro_dfu.py readdiag prints them with these names).  Only blocks that are clocked
 * at this point: reading an unclocked peripheral faults on the LPC55 (the first diagnostic build did exactly that).
 */
static const uint32_t diag_regs[DIAG_NREGS] = {
	0x40084000u, 0x40084004u, 0x40084020u, 0x40084024u,   /* USB0: DEVCMDSTAT, INFO, INTSTAT, INTEN */
	0x40084008u, 0x4008400Cu,                             /* USB0: EPLISTSTART, DATABUFSTART */
	0x40000200u, 0x40000204u, 0x40000208u,                /* SYSCON AHBCLKCTRL0-2 */
	0x40000100u, 0x40000104u, 0x40000108u,                /* SYSCON PRESETCTRL0-2 */
	0x40000280u, 0x40000284u, 0x40000380u,                /* MAINCLKSELA, MAINCLKSELB, AHBCLKDIV */
	0x400002A8u, 0x40000398u, 0x400002A4u, 0x40000394u,   /* USB0CLKSEL, USB0CLKDIV, ADCCLKSEL, ADCCLKDIV */
	0x40000400u,                                          /* FMCCR */
	0x400200B8u,                                          /* PMC PDRUNCFG0 */
	0x40013010u, 0x40013014u,                             /* ANACTRL FRO192M_CTRL, FRO192M_STATUS */
	USBFSH_PORTMODE_ADDR,                                 /* read with its clock switched on, see snapshot() */
	0x40034FE0u,                                          /* FLASH INT_STATUS */
	0xE000ED08u, 0xE000ED04u, 0xE000E100u, 0xE000E104u,   /* VTOR, ICSR, NVIC ISER0-1 */
	0xE000E200u, 0x400A0014u, 0x400A0010u,                /* NVIC ISPR0, ADC STAT, ADC CTRL */
};

#define REPORT_AFTER_CYCLES 144000000u   /* 3 s at 48 MHz (12 s if the clock switch did not happen) */

static uint32_t elapsed, last;
static int started, reporting;

/*
 * Timing channel.  When this was written the record could not reach flash (writes only work after flash_power_init,
 * found later, and a visit to the bootloader clears the page anyway).  So, before restarting into the bootloader, a
 * report waits 1.5 s x (1 + USB bits) and tools/v2pro_dfu.py prints how long after DFUExit the bootloader came back
 * (4.4 s with no wait: 3 s to the report, plus start-up and enumeration).  A fault while reporting waits nothing.
 */
#define USB_BIT_DCON    1u   /* USB0 DEVCMDSTAT.DCON: pull-up connected */
#define USB_BIT_VBUS    2u   /* DEVCMDSTAT.VBUSDEBOUNCED */
#define USB_BIT_IRQ     4u   /* at least one USB0 interrupt taken */
#define USB_BIT_DEV_EN  8u   /* DEVCMDSTAT.DEV_EN */

static void signal_and_enter(uint32_t units) __attribute__((noreturn));
static void signal_and_enter(uint32_t units)
{
	uint64_t wait = (uint64_t)units * 72000000u;   /* 1.5 s per unit at 48 MHz */
	uint32_t prev = SYST_CVR;

	SYST_RVR = 0x00FFFFFFu;
	SYST_CSR = 5u;
	while (wait) {
		uint32_t now = SYST_CVR, d = (prev - now) & 0x00FFFFFFu;

		prev = now;
		wait = d >= wait ? 0 : wait - d;
	}
	razer_bootloader_enter();
}

/*
 * A fault can come before USB or the ADC are clocked, and reading an unclocked block from the fault handler faults
 * again, which locks the core up (no reset, no bootloader: seen on hardware).  So those blocks are only read when
 * their clock is on; otherwise the snapshot holds 0xDEADC10C.
 */
#define AHBCLKCTRL(n) (*(volatile uint32_t *)(0x40000200u + 4u * (n)))

static uint32_t snapshot_reg(uint32_t addr)
{
	uint32_t v;

	if ((addr >> 12 == 0x40084u && !(AHBCLKCTRL(1) & AHBCLK1_USB0D)) ||
	    (addr >> 12 == 0x400A0u && !(AHBCLKCTRL(0) & AHBCLK0_ADC)) ||
	    (addr >> 12 == 0x40013u && !(AHBCLKCTRL(2) & AHBCLK2_ANALOG_CTRL))) {
		return 0xDEADC10Cu;
	}
	if (addr != USBFSH_PORTMODE_ADDR) {
		return *(volatile uint32_t *)addr;
	}
	SYSCON_AHBCLKCTRLSET(2) = AHBCLK2_USB0HSL;
	v = *(volatile uint32_t *)addr;
	SYSCON_AHBCLKCTRLCLR(2) = AHBCLK2_USB0HSL;
	return v;
}

static void report(void) __attribute__((noreturn));
static void report(void)
{
	uint8_t page[FLASH_PAGE_SIZE];
	uint32_t primask;
	unsigned i;

	reporting = 1;   /* a fault from here on goes straight to the bootloader (diag_fault) */
	__asm volatile ("mrs %0, primask" : "=r"(primask));
	diag.primask = primask;
	memcpy(diag.magic, DIAG_MAGIC, sizeof(diag.magic));
	for (i = 0; i < DIAG_NREGS; i++) {
		diag.regs[i] = snapshot_reg(diag_regs[i]);
	}
	memset(page, 0xFF, sizeof(page));
	memcpy(page, &diag, sizeof(diag));
	/* Still attempted: harmless, and it shows at once if a later build gets flash writes working. */
	if (flash_erase_page(DIAG_RECORD_ADDR) == 0) {
		(void)flash_program_page(DIAG_RECORD_ADDR, page);
	}
	if (diag.fault_vector) {   /* a fault: 20 + the last DIAG_STEP reached; 1..16 are the USB codes */
		signal_and_enter(20u + diag_breadcrumb);
	}
	{
		uint32_t dev = diag.regs[0], bits = 0;

		bits |= (dev & (1u << 16)) ? USB_BIT_DCON : 0;
		bits |= (dev & (1u << 28)) ? USB_BIT_VBUS : 0;
		bits |= diag.usb_irqs ? USB_BIT_IRQ : 0;
		bits |= (dev & (1u << 7)) ? USB_BIT_DEV_EN : 0;
		signal_and_enter(1u + bits);
	}
}

void diag_poll(int usb_configured)
{
	uint32_t now;

	diag.loops++;
	if (usb_configured) {
		DIAG_STAGE(DS_CONFIGURED);
		return;
	}
	if (!started) {   /* SysTick as a free-running 24-bit down-counter on the CPU clock, no interrupt */
		SYST_RVR = 0x00FFFFFFu;
		SYST_CVR = 0;
		SYST_CSR = 5u;
		last = SYST_CVR;
		started = 1;
		return;
	}
	now = SYST_CVR;
	elapsed += (last - now) & 0x00FFFFFFu;
	last = now;
	if (elapsed >= REPORT_AFTER_CYCLES) {
		report();
	}
}

void diag_fault(const uint32_t *frame)
{
	if (reporting) {
		signal_and_enter(0);   /* a fault while reporting: no wait */
	}
	diag.fault_vector = *(volatile uint32_t *)0xE000ED04u & 0x1FFu;   /* ICSR.VECTACTIVE */
	diag.cfsr = *(volatile uint32_t *)0xE000ED28u;
	diag.hfsr = *(volatile uint32_t *)0xE000ED2Cu;
	diag.bfar = *(volatile uint32_t *)0xE000ED38u;
	/* The frame is on our stack in SRAMX (0x04000000-0x04007FFF) unless the stack itself is what broke. */
	if ((uint32_t)frame >= 0x04000000u && (uint32_t)frame <= 0x04008000u - 32u) {
		diag.fault_lr = frame[5];
		diag.fault_pc = frame[6];
	}
	/*
	 * No snapshot and no flash attempt here: a fault can come before USB is clocked or out of reset, and any access
	 * that faults again inside this handler locks the core up (seen twice on hardware).  Only core registers above,
	 * then straight to the timing channel: 20 + the last DIAG_STEP.
	 */
	reporting = 1;
#ifdef DIAG_SIGNAL_VECTOR
	signal_and_enter(20u + diag.fault_vector);   /* temporary: which exception (3 HardFault .. 7 SecureFault) */
#elif defined(DIAG_SIGNAL_BFAR)
	{
		/* Temporary: which address the precise bus error hit. */
		uint32_t a = diag.bfar, cls;

		if (!(diag.cfsr & (1u << 15))) {
			cls = 1;                                  /* BFAR not valid */
		} else if (a == 0x40085004u) {
			cls = 2;                                  /* SCT CTRL */
		} else if (a == 0x40085100u) {
			cls = 3;                                  /* SCT MATCH0 */
		} else if (a == 0x40085200u) {
			cls = 4;                                  /* SCT MATCHREL0 */
		} else if (a >= 0x40085000u && a < 0x40086000u) {
			cls = 5;                                  /* elsewhere in the SCT */
		} else {
			cls = 6;
		}
		signal_and_enter(20u + cls);
	}
#elif defined(DIAG_SIGNAL_FAULT_CLASS)
	{
		/* Temporary: what kind of fault, from the status registers (everything escalates to HardFault here). */
		uint32_t sfsr = *(volatile uint32_t *)0xE000EDE4u, c = diag.cfsr, cls = 12;
		static const uint8_t ufsr_bits[] = {16, 17, 18, 19, 20, 24, 25};   /* -> classes 4..10 */
		unsigned i;

		if (sfsr) {
			cls = 1;
		} else if (c & 0xFF00u) {
			cls = (c & (1u << 9)) ? 2 : 3;          /* PRECISERR : IMPRECISERR / other bus error */
		} else if (c & 0xFFFF0000u) {
			for (i = 0; i < sizeof(ufsr_bits); i++) {
				if (c & (1u << ufsr_bits[i])) {
					cls = 4 + i;
					break;
				}
			}
		} else if (c & 0xFFu) {
			cls = 11;
		}
		signal_and_enter(20u + cls);
	}
#else
	signal_and_enter(20u + diag_breadcrumb);
#endif
}

#else

void diag_poll(int usb_configured)
{
	(void)usb_configured;
}

void diag_fault(const uint32_t *frame)
{
	(void)frame;
	razer_bootloader_enter();
}

#endif
