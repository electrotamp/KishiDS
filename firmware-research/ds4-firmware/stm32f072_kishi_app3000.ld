/* Kishi 0290 vendor-bootloader application slot, corrected 2026-10-06.
 *
 * Static analysis of stock v2.70 shows the application is linked at
 * 0x08003000 (crt0 literal pools, .data load address 0x08009AE8, default
 * handler `b .` at 0x08009890), NOT 0x08009000.  The vendor bootloader
 * occupies 0x08000000-0x08002FFF.  The stock app also references the flash
 * page at 0x0800F800 (likely settings) - keep clear of it.
 *
 * Stock .data starts at 0x20000100; 0x20000000-0x200000FF is left free,
 * consistent with a bootloader-copied/remapped vector table.  Keep it free.
 */
MEMORY
{
	rom (rx)  : ORIGIN = 0x08003000, LENGTH = 0x7000
	ram (rwx) : ORIGIN = 0x20000100, LENGTH = 16K - 0x100
}

INCLUDE ../third_party/libopencm3/lib/cortex-m-generic.ld
