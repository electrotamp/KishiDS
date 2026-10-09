/*
 * USB flight recorder for the Kishi V2 Pro: what a host did during the first seconds after power-on (descriptor and
 * report requests, configuration, lightbar / rumble it sent, resets since power-on), written to flash once, 8 s
 * after start-up, so a session on a host we cannot watch (an iPhone) can be read afterwards from a PC.
 *
 * Two flash slots (0x1E200, 0x1E400) are used in turn with a sequence number, so the PC session that reads the log
 * does not overwrite the one before it.  Read with tools/v2pro_probe.py usblog (KishiDS live READSEL 8..11).
 */
#ifndef USBLOG_V2PRO_H
#define USBLOG_V2PRO_H

#include <stdint.h>

#define USBLOG_MAGIC 0x474F4C4Bu   /* "KLOG" */

struct usblog {
	uint32_t magic;
	uint32_t seq;                /* one more than the newest slot found at start-up */
	uint8_t boots;               /* starts since power-on (RAM counter kept across resets): >1 = it was reset */
	uint8_t configured;          /* the host configured the device */
	uint8_t n_dev, n_cfg;        /* descriptor requests: device, configuration */
	uint8_t n_str, n_rep;        /* string descriptors, HID report descriptor */
	uint8_t n_get, n_set;        /* HID GET_REPORT / SET_REPORT (feature) */
	uint8_t n_out;               /* output reports (lightbar / rumble) */
	uint8_t rgb[3];              /* last lightbar colour received */
	uint8_t rumble[2];           /* last weak / strong rumble received */
	uint8_t n_mount, n_suspend;  /* TinyUSB mount / suspend events */
	uint16_t str_mask;           /* bit n: string descriptor index n was requested (n < 16) */
	uint16_t pad0;
	uint32_t ms_configured;      /* when (ms after start-up), 0 if never */
	uint32_t usb_irqs;
	uint8_t get_ids[8];          /* first feature report IDs the host read */
	uint8_t set_ids[4];          /* first feature report IDs the host wrote */
};

extern struct usblog usblog;

/* First thing in main: count this start in the RAM kept across resets, read the newest slot's sequence number. */
void usblog_start(void);

/* Main loop: writes the record once, 8 s after start-up. */
void usblog_poll(uint32_t ms);

void usblog_get_report(uint8_t report_id);
void usblog_set_report(uint8_t report_id);

#endif
