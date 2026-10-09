/*
 * The wired DualShock 4 USB profile shared by every board: HID report descriptor and the fixed feature reports a real
 * DS4 answers.  Board-independent (no USB stack), so the V1 (libopencm3) and the V2 Pro (TinyUSB) serve the same bytes.
 *
 * The data is what the V1 has served since its first release (verified on an iPhone).
 */
#ifndef DS4_USB_H
#define DS4_USB_H

#include <stdint.h>

#define DS4_VID 0x054Cu
#define DS4_PID 0x05C4u

/* Fixed by the DS4 profile; ds4_usb.c checks the table against it at compile time. */
#define DS4_REPORT_DESCRIPTOR_LEN 483u

extern const uint8_t ds4_report_descriptor[DS4_REPORT_DESCRIPTOR_LEN];
extern const uint16_t ds4_report_descriptor_len;

/*
 * GET_REPORT for the fixed feature reports (0x02, 0x12, 0x81, 0xA3, and every other feature ID the descriptor declares,
 * answered with zeros).  Returns the length and points *data at the reply (possibly inside scratch), or 0 when
 * report_id is not one of them; the caller handles the dynamic ones (input report 0x01, KishiDS telemetry 0xAB, live
 * editing 0xAC).
 */
/*
 * Replace the fixed Bluetooth address (6 bytes, least significant first) in features 0x12 and 0x81.  iOS remembers a
 * DS4 by it: two controllers answering the same address are taken for one (the second is not listed until the first
 * is forgotten).  Without a call, every board answers the same captured address.
 */
void ds4_set_device_address(const uint8_t addr[6]);

uint16_t ds4_fixed_feature(uint8_t report_id, uint8_t scratch[64], const uint8_t **data);

#endif
