/*
 * Vector table and reset handler for the application slot at 0x8000 (Razer's bootloader occupies 0x0000-0x7FFF and
 * jumps here).  Razer's own image uses a plain Cortex-M vector table with no LPC55 boot header (words 8-10 are zero),
 * so this does the same.  The CPU starts on the 12 MHz FRO; main() switches to 48 MHz (clock.c).
 */
#include <stdint.h>
#include <string.h>

#include "bootloader.h"
#include "diag.h"
#include "lpc55.h"

extern uint32_t _estack, _sidata, _sdata, _edata, _sbss, _ebss;
int main(void);

void Reset_Handler(void);
void Default_Handler(void);
void USB0_IRQHandler(void);   /* usb_ds4.c */

#define LPC55_IRQ_COUNT 60
#define USB0_IRQ        28

extern const void *const vectors[16 + LPC55_IRQ_COUNT];

/*
 * Razer's bootloader jumps here with the core as it left it: on hardware our first image ran (View + Menu worked) but
 * USB never came up.  Razer's own reset handler disables interrupts, runs SystemInit (0xecfc), which points VTOR at
 * the application's table (0x8000), and enables interrupts again before main.  Do the same, and also stop what the
 * bootloader may leave running (SysTick, enabled or pending NVIC lines), since anything unexpected that fires lands in
 * Default_Handler, which restarts into the bootloader.
 */
void Reset_Handler(void)
{
	unsigned i;

	__asm volatile ("cpsid i" ::: "memory");
	diag_start_cycles();
	SCB_VTOR = (uint32_t)vectors;
	SYST_CSR = 0;
	for (i = 0; i < (LPC55_IRQ_COUNT + 31) / 32; i++) {
		SCB_NVIC_ICER(i) = 0xFFFFFFFFu;
		SCB_NVIC_ICPR(i) = 0xFFFFFFFFu;
	}
	__asm volatile ("dsb\n\tisb" ::: "memory");
	memcpy(&_sdata, &_sidata, (size_t)((char *)&_edata - (char *)&_sdata));
	memset(&_sbss, 0, (size_t)((char *)&_ebss - (char *)&_sbss));
	DIAG_STAGE(DS_RESET);
	__asm volatile ("cpsie i" ::: "memory");
	main();
	for (;;) {
	}
}

/*
 * Every fault and every interrupt we do not use lands here.  Hanging would leave a controller with no way to be
 * re-flashed (no USB, so no software entry), so restart into Razer's bootloader instead: a firmware that faults on
 * every boot then ends up in DFU mode, where v2pro_dfu.py flash restores Razer's image.  The stacked frame goes to
 * diag_fault(), which records it first in diagnostic builds.
 */
__attribute__((naked)) void Default_Handler(void)
{
	__asm volatile (
		"tst lr, #4\n\t"
		"ite eq\n\t"
		"mrseq r0, msp\n\t"
		"mrsne r0, psp\n\t"
		"b diag_fault\n\t");
}

__attribute__((section(".isr_vector"), used))
const void *const vectors[16 + LPC55_IRQ_COUNT] = {
	[0] = &_estack,
	[1] = Reset_Handler,
	[2 ... 6] = Default_Handler,        /* NMI, HardFault, MemManage, BusFault, UsageFault */
	[7] = Default_Handler,              /* SecureFault */
	[11] = Default_Handler,             /* SVCall */
	[12] = Default_Handler,             /* DebugMonitor */
	[14] = Default_Handler,             /* PendSV */
	[15] = Default_Handler,             /* SysTick */
	[16 ... 16 + USB0_IRQ - 1] = Default_Handler,
	[16 + USB0_IRQ] = USB0_IRQHandler,
	[16 + USB0_IRQ + 1 ... 16 + LPC55_IRQ_COUNT - 1] = Default_Handler,
};
