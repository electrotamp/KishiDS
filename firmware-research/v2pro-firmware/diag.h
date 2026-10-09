/*
 * Start-up diagnostics.  Every hardware wait in the start-up path is bounded (WAIT_UNTIL): on real hardware a status
 * bit that never comes would otherwise hang the controller with no USB and no way to say why.  A timed-out wait sets
 * its bit in diag.timeouts and start-up carries on.
 *
 * Diagnostic builds (./build.sh diag, V2PRO_DIAG) also record the stages reached and, if USB is not configured a few
 * seconds after start-up, write `diag` plus a register snapshot to the saved-settings page (0x1E000) and restart into
 * Razer's bootloader by themselves.  tools/v2pro_dfu.py readdiag then reads the page back through the bootloader
 * (DFUVerify), with no USB from our firmware needed.
 */
#ifndef DIAG_H
#define DIAG_H

#include <stdint.h>

#define DIAG_MAGIC   "KDSDIAG1"
#define DIAG_NREGS   32u
#define WAIT_LIMIT   4000000u   /* polls; far longer than any of these waits should take (~1 s at 12 MHz) */

enum diag_wait {
	DW_FLASH_READ_MODE, DW_AHBCLKDIV, DW_USB0CLKDIV, DW_PRESET_SET, DW_PRESET_CLR, DW_ADCCLKDIV,
	DW_ADC_CALOFS, DW_ADC_GCC, DW_ADC_CAL_RDY, DW_FLASH_CMD, DW_SCTCLKDIV,
};
enum diag_stage {
	DS_RESET, DS_COMBO, DS_CLOCK, DS_BOARD, DS_CONFIG, DS_USB_INIT, DS_LOOP, DS_USB_IRQ, DS_MOUNTED, DS_CONFIGURED,
	DS_COUNT
};

struct diag {
	char magic[8];
	uint32_t stages;     /* bit per enum diag_stage reached */
	uint32_t timeouts;   /* bit per enum diag_wait that gave up */
	uint32_t usb_irqs;
	uint32_t loops;      /* main loop passes */
	uint32_t primask;    /* when the snapshot was taken */
	/* Set when a fault or unexpected interrupt triggered the report (fault_vector 0: it was the USB timeout). */
	uint32_t fault_vector, cfsr, hfsr, bfar, fault_pc, fault_lr;
	uint32_t regs[DIAG_NREGS];   /* see diag_regs in diag.c */
};

extern struct diag diag;

/*
 * CPU cycle count (DWT CYCCNT, started in Reset_Handler) when each stage was first reached, in every build: telemetry
 * report 0xAB carries them (usb_ds4.c) so tools/v2pro_probe.py boottime can show where start-up time goes.  The CPU
 * runs at 12 MHz until DS_CLOCK and 48 MHz after.
 */
extern uint32_t diag_stamp[DS_COUNT];

#ifdef KCFG_HOST_TEST
#define DIAG_CYCLES() 0u
#else
#define DIAG_CYCLES() (*(volatile uint32_t *)0xE0001004u)   /* DWT_CYCCNT */
#endif

static inline void diag_stage(enum diag_stage s)
{
	if (!(diag.stages & (1u << s))) {
		diag.stages |= 1u << s;
		diag_stamp[s] = DIAG_CYCLES();
	}
}

#define DIAG_STAGE(s) diag_stage(s)

/* Start the cycle counter (first thing in Reset_Handler). */
void diag_start_cycles(void);

/*
 * Last step a piece of start-up code under investigation reached (DIAG_STEP(n)).  A diagnostic build that faults
 * reports it through the timing channel (diag.c), so a fault can be placed without flash or USB.
 */
extern volatile uint8_t diag_breadcrumb;
#define DIAG_STEP(n) (diag_breadcrumb = (uint8_t)(n))

#define WAIT_UNTIL(cond, id)                                   \
	do {                                                       \
		uint32_t wait_n_ = 0;                                  \
		while (!(cond)) {                                      \
			if (++wait_n_ >= WAIT_LIMIT) {                     \
				diag.timeouts |= 1u << (id);                   \
				break;                                         \
			}                                                  \
		}                                                      \
	} while (0)

/* Diagnostic builds: call once per main-loop pass; reports and enters the bootloader when USB stays down. */
void diag_poll(int usb_configured);

/*
 * Every fault and unused interrupt (startup.c), with the stacked exception frame.  Diagnostic builds record it first;
 * all builds then restart into Razer's bootloader.  Never returns.
 */
void diag_fault(const uint32_t *frame) __attribute__((noreturn));

#endif
