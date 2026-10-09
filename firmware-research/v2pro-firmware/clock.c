/*
 * Clocks and USB0 bring-up, register-level.  The sequences follow NXP's fsl_clock / TinyUSB's LPC55 board code, and
 * every register they write also shows up in Razer's own start-up (emulated trace of firmware 2.2), with the same
 * flash wait-state value.
 *
 * Deliberate difference from Razer: Razer runs the CPU at 96 MHz after raising the core voltage through the PMC, with
 * values computed from per-chip factory trims.  We do not touch the voltage at all and run the CPU at 48 MHz (FRO_HF
 * 96 MHz / 2), which the reset voltage supports; USB gets its own 48 MHz from the same FRO, trimmed by the USB SOF.
 */
#include "clock.h"

#include <string.h>

#include "diag.h"
#include "lpc55.h"

extern uint32_t _susb_ram, _eusb_ram;   /* v2pro_app8000.ld */

/* Razer's value (Razer runs 96 MHz; NXP's table gives 8 for <= 100 MHz).  More wait states than needed is only slower. */
#define FLASH_WAIT_STATES 8u

static void wait_divider(volatile uint32_t *div, enum diag_wait id)
{
	WAIT_UNTIL(!(*div & CLKDIV_REQFLAG), id);
}

void clock_peripheral_reset(unsigned reg, uint32_t bit)
{
	SYSCON_PRESETCTRLSET(reg) = bit;
	WAIT_UNTIL(SYSCON_PRESETCTRL(reg) & bit, DW_PRESET_SET);
	SYSCON_PRESETCTRLCLR(reg) = bit;
	WAIT_UNTIL(!(SYSCON_PRESETCTRL(reg) & bit), DW_PRESET_CLR);
}

static void set_flash_wait_states(uint32_t ws)
{
	uint32_t prefetch = SYSCON_FMCCR & FMCCR_PREFEN;

	SYSCON_FMCCR &= ~FMCCR_PREFEN;            /* prefetch off before any flash command */
	FLASH_INT_CLR_STATUS = 0x1Fu;
	FLASH_DATAW0 = (FLASH_DATAW0 & ~0xFu) | (ws & 0xFu);
	FLASH_CMD = FLASH_CMD_SET_READ_MODE;
	WAIT_UNTIL(FLASH_INT_STATUS & FLASH_INT_DONE, DW_FLASH_READ_MODE);
	SYSCON_FMCCR = (SYSCON_FMCCR & ~FMCCR_FLASHTIM_MASK) | ((ws << 12) & FMCCR_FLASHTIM_MASK);
	SYSCON_FMCCR |= prefetch;
}

void clock_init(void)
{
	/* Wait states first, while still on the 12 MHz FRO. */
	set_flash_wait_states(FLASH_WAIT_STATES);

	/* FRO192M: analog control block out of reset and clocked, FRO powered, 96 MHz output on. */
	SYSCON_PRESETCTRLCLR(2) = AHBCLK2_ANALOG_CTRL;
	SYSCON_AHBCLKCTRLSET(2) = AHBCLK2_ANALOG_CTRL;
	PMC_PDRUNCFGCLR0 = PDRUNCFG_FRO192M;
	ANACTRL_FRO192M_CTRL |= FRO192M_ENA_96MHZCLK;

	/* CPU = FRO_HF / 2 = 48 MHz: set the divider before switching the source. */
	SYSCON_AHBCLKDIV = 1u;
	wait_divider(&SYSCON_AHBCLKDIV, DW_AHBCLKDIV);
	SYSCON_MAINCLKSELA = 3u;
	SYSCON_MAINCLKSELB = 0u;
}

void clock_usb0_init(void)
{
	PMC_PDRUNCFGCLR0 = PDRUNCFG_USB0_PHY;

	clock_peripheral_reset(1, AHBCLK1_USB0D);
	clock_peripheral_reset(2, AHBCLK2_USB0HSL);
	clock_peripheral_reset(2, AHBCLK2_USB0HMR);

	/* USB0 clock = FRO_HF / 2 = 48 MHz, kept on frequency by the SOF-based trim (no crystal needed). */
	ANACTRL_FRO192M_CTRL |= FRO192M_USBCLKADJ;
	/*
	 * Source first, then the divider (NXP's order: attach, then set the divider).  A divider only acknowledges a change
	 * (REQFLAG clears) while its source runs, and USB0CLKSEL resets to "none": the other way round, this wait timed out
	 * on hardware and added 750 ms to every start-up (v2pro_probe.py boottime).
	 */
	SYSCON_USB0CLKSEL = 3u;
	SYSCON_USB0CLKDIV = 1u;
	wait_divider(&SYSCON_USB0CLKDIV, DW_USB0CLKDIV);

	/* The port belongs to the device controller; the switch lives in the host block, which needs its clock briefly. */
	SYSCON_AHBCLKCTRLSET(2) = AHBCLK2_USB0HSL;
	USBFSH_PORTMODE |= PORTMODE_DEV_ENABLE;
	SYSCON_AHBCLKCTRLCLR(2) = AHBCLK2_USB0HSL;

	SYSCON_AHBCLKCTRLSET(1) = AHBCLK1_USB0D;
	SYSCON_AHBCLKCTRLSET(2) = AHBCLK2_USB1RAM;
	/* TinyUSB's buffers live in the USB RAM (tusb_config.h), which Reset_Handler's .bss clear does not cover. */
	memset(&_susb_ram, 0, (size_t)((char *)&_eusb_ram - (char *)&_susb_ram));
}
