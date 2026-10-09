/* TinyUSB configuration: one full-speed device port (USB0) with a single HID interface (the DS4). */
#ifndef TUSB_CONFIG_H
#define TUSB_CONFIG_H

#define CFG_TUSB_MCU            OPT_MCU_LPC55
#define CFG_TUSB_OS             OPT_OS_NONE
#define CFG_TUSB_DEBUG          0

#define CFG_TUD_ENABLED         1
#define CFG_TUSB_RHPORT0_MODE   (OPT_MODE_DEVICE | OPT_MODE_FULL_SPEED)
#define CFG_TUD_MAX_SPEED       OPT_MODE_FULL_SPEED
#define CFG_TUD_ENDPOINT0_SIZE  64

#define CFG_TUD_HID             1
#define CFG_TUD_CDC             0
#define CFG_TUD_MSC             0
#define CFG_TUD_MIDI            0
#define CFG_TUD_VENDOR          0
#define CFG_TUD_HID_EP_BUFSIZE  64

/*
 * Endpoint list and every buffer the USB controller reads or writes go in the USB RAM (0x40100000), where Razer's
 * driver keeps them (state at 0x12144: 0x40100200).  On hardware, with them in ordinary SRAM, the controller came up
 * (pull-up, VBUS, interrupts) but the host never got a configured device.  Not on the host build (no such memory).
 */
#ifndef KCFG_HOST_TEST
#define CFG_TUD_MEM_SECTION     __attribute__((section(".usb_ram")))
#endif

#endif
