/*
 * Kishi V2 Pro DualShock 4 firmware: main loop.  Reads the buttons, builds DS4 reports with the shared V1 logic
 * (config.c, report.c, live.c) and sends them over USB (TinyUSB).  The ADC is not implemented yet (sticks centred).
 * See README.md for the status.
 */
#include <stdint.h>

#include "board.h"
#include "bootloader.h"
#include "clock.h"
#include "config.h"
#include "diag.h"
#include "haptics_v2pro.h"
#include "led_v2pro.h"
#include "live.h"
#include "persist.h"
#include "report.h"
#include "usb_ds4.h"
#include "usblog_v2pro.h"

static struct kishi_cal cal;
static uint8_t input_report[KISHI_REPORT_SIZE];

/* Called by live.c when a new configuration becomes active: refresh the calibration cached from it (as on the V1). */
void hw_apply_config(void)
{
	report_cal_from_config(&cal, &kcfg);
}

/* Free-running milliseconds from the DWT cycle counter (48 MHz after clock_init); call at least every 89 s. */
static uint32_t millis(void)
{
	static uint32_t last_cycles, ms, rem;
	uint32_t now = DIAG_CYCLES(), d = now - last_cycles + rem;

	last_cycles = now;
	ms += d / 48000u;
	rem = d % 48000u;
	return ms;
}

int main(void)
{
	uint16_t adc[KISHI_ADC_COUNT];
	uint8_t counter = 0;

	/* Before anything that could fail (clocks, USB): the one way into Razer's bootloader that needs no USB. */
	if (board_boot_combo_held()) {
		razer_bootloader_enter();
	}
	DIAG_STAGE(DS_COMBO);
	clock_init();
	DIAG_STAGE(DS_CLOCK);
	board_init();
	DIAG_STAGE(DS_BOARD);
	persist_load();
	usblog_start();
	kcfg_load();
	live_init();
	hw_apply_config();
	DIAG_STAGE(DS_CONFIG);
	led_init();
	usb_ds4_init();
	DIAG_STAGE(DS_USB_INIT);

	for (;;) {
		uint32_t buttons = board_to_kishi_buttons(board_read_buttons());

		DIAG_STAGE(DS_LOOP);
		if (usb_ds4_configured()) {
			DIAG_STAGE(DS_CONFIGURED);   /* its time stamp: how long start-up and enumeration took (telemetry 0xAB) */
		}
		diag_poll(usb_ds4_configured());
		usb_ds4_task();
		board_read_adc(adc);
		usb_ds4_set_telemetry(buttons, adc);
		report_build(&kcfg, &cal, buttons, adc, counter, input_report);
		if (usb_ds4_configured() && usb_ds4_send(input_report) == 0) {
			counter++;
		}
		{
			uint32_t ms = millis();

			if (usb_ds4_configured() && !usblog.configured) {
				usblog.configured = 1;
				usblog.ms_configured = ms;
			}
			usblog_poll(ms);
			led_update(ms, usb_ds4_configured());
			haptics_update(ms, usb_ds4_configured() ? rumble_strong : 0, usb_ds4_configured() ? rumble_weak : 0);
		}
		live_service();
	}
}
