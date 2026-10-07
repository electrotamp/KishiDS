/* Kishi 0290 vendor-bootloader application slot. */
MEMORY
{
	rom (rx)  : ORIGIN = 0x08009000, LENGTH = 28K
	ram (rwx) : ORIGIN = 0x20000000, LENGTH = 16K
}

INCLUDE ../third_party/libopencm3/lib/cortex-m-generic.ld
