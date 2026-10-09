/*
 * Kishi V2 Pro application slot.  Razer's bootloader: 0x00000-0x07FFF (never touched).  Razer's 2.2 image spans
 * 0x08000-0x1EBFF; we stay inside that span so the bootloader's erase/program range is one we know it accepts.
 *   0x08000-0x1DFFF  code + read-only data (88 KB)
 *   0x1E000-0x1E1FF  saved-settings record, shipped as programmed 0xFF (LPC55: never leave it erased-unprogrammed)
 * RAM: .data/.bss in SRAM at 0x20000000 (64 KB, present on every 640 KB LPC55); stack at the top of the 32 KB SRAMX
 * (0x04008000), the same place Razer's firmware puts it.
 */
MEMORY
{
	FLASH (rx)  : ORIGIN = 0x00008000, LENGTH = 0x16000
	SAVED (r)   : ORIGIN = 0x0001E000, LENGTH = 0x200
	RAM   (rwx) : ORIGIN = 0x20000000, LENGTH = 64K
	USBRAM (rw) : ORIGIN = 0x40100000, LENGTH = 16K   /* clock: AHBCLKCTRL2 USB1RAM (clock.c); zeroed there too */
}

_estack = 0x04008000;

SECTIONS
{
	.text : {
		KEEP(*(.isr_vector))
		KEEP(*(.recovery_marker))   /* bootloader.c: v2pro_dfu.py looks for it */
		*(.text*)
		*(.rodata*)
		. = ALIGN(4);
	} > FLASH

	.ARM.exidx : { *(.ARM.exidx*) } > FLASH
	_etext = .;

	/* build.sh drops .data from the .bin while it is empty: ld then keeps its RAM address as LMA (AT() is ignored for an
	   empty section), and objcopy -O binary would pad the image all the way to 0x20000000. */
	_sidata = LOADADDR(.data);
	.data : AT(_etext) {
		_sdata = .;
		*(.ramfunc*)   /* code that must not run from flash (flash.c: commands that erase/program the array) */
		*(.data*)
		. = ALIGN(4);
		_edata = .;
	} > RAM

	.bss (NOLOAD) : {
		_sbss = .;
		*(.bss*)
		*(COMMON)
		. = ALIGN(4);
		_ebss = .;
	} > RAM

	/* TinyUSB's endpoint list and transfer buffers (tusb_config.h CFG_TUD_MEM_SECTION), as Razer's driver does. */
	.usb_ram (NOLOAD) : {
		_susb_ram = .;
		*(.usb_ram*)
		. = ALIGN(4);
		_eusb_ram = .;
	} > USBRAM

	/* Placed last so the image runs contiguously from 0x8000 to the end of this page (gaps are filled with 0xFF). */
	.kcfg_saved : { KEEP(*(.kcfg_saved)) } > SAVED
}
