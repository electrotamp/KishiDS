/*
 * Internal flash: erase and program 512-byte pages, and read through the flash controller so that an erased or
 * half-written page reports an error instead of faulting the CPU (what a plain bus read does on the LPC55).
 */
#ifndef FLASH_H
#define FLASH_H

#include <stdint.h>

#define FLASH_APP_FIRST 0x00008000u   /* never touch Razer's bootloader below this */

/*
 * Power and supervisor set-up Razer's firmware does right before its flash driver init (0x1659e..0x1664e): PDRUNCFG0
 * bits 9 and 23 on, the VBAT brown-out detector at 0x53 and brown-out reset enabled.  On hardware, erase/program
 * changed nothing without it and work with it (a saved record survives a power cycle).  Call once at start-up.
 */
void flash_power_init(void);

/* 0 on success, -1 on a controller error or an address outside [FLASH_APP_FIRST, end of our image span). */
int flash_erase_page(uint32_t addr);
int flash_program_page(uint32_t addr, const uint8_t data[512]);

/* Read len bytes (multiple of 16, 16-byte aligned) through the controller.  -1 if any word cannot be read. */
int flash_read_safe(uint32_t addr, uint8_t *out, uint32_t len);

#endif
