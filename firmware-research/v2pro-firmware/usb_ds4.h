/*
 * USB device side: enumerate as a wired DualShock 4 (TinyUSB on USB0, full speed) and serve the same reports as the
 * V1 firmware: input report 0x01, the fixed DS4 feature reports (../ds4-firmware/ds4_usb.c), KishiDS telemetry 0xAB
 * and live editing 0xAC.
 */
#ifndef USB_DS4_H
#define USB_DS4_H

#include <stdint.h>

#include "kishi_io.h"

/* Clocks USB0, copies the USB strings from kcfg (fixed for the session, as on the V1) and connects. */
void usb_ds4_init(void);

/* Run TinyUSB's event loop; call from the main loop. */
void usb_ds4_task(void);

/* 1 once the host has configured the device (never write the IN endpoint before that; V1 lesson). */
int usb_ds4_configured(void);

/* Queue one 64-byte DS4 input report (ID 0x01 first); returns 0 if queued, -1 if busy or not configured. */
int usb_ds4_send(const uint8_t report[64]);

/* Latest physical state for the KishiDS telemetry report 0xAB (shared Kishi button bits, raw ADC). */
void usb_ds4_set_telemetry(uint32_t buttons, const uint16_t adc[KISHI_ADC_COUNT]);

/* Last rumble levels the host asked for (DS4 output report 0x05), 0..255; also in telemetry 0xAB bytes 54-55. */
extern uint8_t rumble_weak, rumble_strong;

#endif
