/* Restart into Razer's bootloader, the way Razer's firmware 2.2 does it (see bootloader.h). */
#include <stdint.h>

#include "bootloader.h"
#include "live.h"
#include "lpc55.h"

/*
 * tools/v2pro_dfu.py flash --custom only accepts images that carry this exact string once: builds that have the ways
 * back into the bootloader below.  Bump the number if the entry mechanism ever changes.
 */
__attribute__((used, section(".recovery_marker")))
const char recovery_marker[] = "KISHIDS-V2PRO-RECOVERY-1";

void razer_bootloader_enter(void)
{
	__asm volatile ("cpsid i" ::: "memory");
	SYSCON_AHBCLKCTRLSET0 = AHBCLK0_SRAM1 | AHBCLK0_SRAM2 | AHBCLK0_SRAM3 | AHBCLK0_SRAM4;
	*(volatile uint32_t *)RAZER_BOOT_FLAG_ADDR = RAZER_BOOT_FLAG_ENTER;
	__asm volatile ("dsb" ::: "memory");
	/* As Razer's reset routine: keep PRIGROUP, request a system reset. */
	SCB_AIRCR = (SCB_AIRCR & 0x700u) | SCB_AIRCR_SYSRESET;
	__asm volatile ("dsb" ::: "memory");
	for (;;) {
	}
}

/* KishiDS live command 7 (live.c, built with LIVE_HAS_BOOTLOADER). */
void hw_enter_bootloader(void)
{
	razer_bootloader_enter();
}
