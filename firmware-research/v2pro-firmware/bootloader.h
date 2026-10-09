/*
 * Way back into Razer's bootloader (1532:110E), so this firmware can always be replaced or Razer's 2.2 restored.
 *
 * Razer's firmware 2.2 does it on SetDeviceMode [0x01, 0x00] (class 0x00 ID 0x04: jump table at 0x18432, case at
 * 0x18552, stub 0x18754 -> 0x140d2): interrupts off, 0xAAAAAAAA written to the last word of SRAM1 (0x20017FFC), then
 * SYSRESETREQ (0x12cb8).  SRAM keeps its contents across that reset and the bootloader stays in DFU mode.  Confirmed
 * on hardware only as a whole (the software entry works with Razer's firmware); the flag itself is read from code.
 * Razer's SystemInit enables the SRAM1-4 clocks (AHBCLKCTRLSET0 = 0x78, at 0xed38); we do the same before the write.
 */
#ifndef BOOTLOADER_H
#define BOOTLOADER_H

#define RAZER_BOOT_FLAG_ADDR  0x20017FFCu
#define RAZER_BOOT_FLAG_ENTER 0xAAAAAAAAu

/* Restart into Razer's bootloader.  Safe from any context, including fault handlers. */
void razer_bootloader_enter(void) __attribute__((noreturn));

#endif
