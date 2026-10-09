/*
 * DualShock 4 over TinyUSB.  Descriptors, endpoints and report handling mirror ../ds4-firmware/main.c (the V1,
 * verified on hardware and on an iPhone) so both boards look identical to the host.
 */
#include "usb_ds4.h"

#include <string.h>

#include "board.h"
#include "clock.h"
#include "config.h"
#include "diag.h"
#include "ds4_usb.h"
#include "haptics_v2pro.h"
#include "led_v2pro.h"
#include "live.h"
#include "usblog_v2pro.h"
#include "tusb.h"

/* Same as the V1: 0x81 IN 64 bytes and 0x01 OUT 32 bytes, both interrupt, 5 ms. */
#define EP_IN       0x81
#define EP_OUT      0x01
#define EP_IN_SIZE  64
#define EP_OUT_SIZE 32
#define CONFIG_LEN  (9 + 9 + 9 + 7 + 7)

static const tusb_desc_device_t device_descriptor = {
	.bLength = sizeof(tusb_desc_device_t),
	.bDescriptorType = TUSB_DESC_DEVICE,
	.bcdUSB = 0x0200,
	.bDeviceClass = 0,
	.bDeviceSubClass = 0,
	.bDeviceProtocol = 0,
	.bMaxPacketSize0 = CFG_TUD_ENDPOINT0_SIZE,
	.idVendor = DS4_VID,
	.idProduct = DS4_PID,
	.bcdDevice = 0x0100,
	.iManufacturer = 1,
	.iProduct = 2,
	.iSerialNumber = 3,
	.bNumConfigurations = 1,
};

/* Built at init: the HID descriptor carries the report descriptor's length, which lives in another translation unit. */
static uint8_t config_descriptor[CONFIG_LEN];

/* USB strings, copied once so a live config change cannot alter them mid-session (as on the V1). */
static char usb_string_text[3][32];
static uint16_t string_buf[33];

/*
 * Serial number and DS4 Bluetooth address, both from the chip's factory UUID (board_uid; Razer's firmware has no USB
 * serial).  If it cannot be read: the placeholder serial and the shared captured address.
 */
static const uint8_t placeholder_serial[KCFG_FACTORY_SERIAL_LEN] = "KISHIV2PRO";
static const uint8_t no_factory_serial[KCFG_FACTORY_SERIAL_LEN];
static const uint32_t no_uid[3];

static void identity_from_uid(char serial[32])
{
	uint8_t uid[16], addr[6];
	uint32_t words[3];
	unsigned i;

	if (board_uid(uid)) {
		kcfg_resolve_serial(serial, placeholder_serial, no_uid);
		return;
	}
	for (i = 0; i < 3; i++) {   /* the first 96 bits, as 24 hex digits in byte order */
		words[i] = (uint32_t)uid[4 * i] << 24 | (uint32_t)uid[4 * i + 1] << 16 | (uint32_t)uid[4 * i + 2] << 8 |
			   uid[4 * i + 3];
	}
	kcfg_resolve_serial(serial, no_factory_serial, words);
	ds4_address_from_uid(uid, sizeof(uid), addr);   /* all 128 bits folded into 48 */
	ds4_set_device_address(addr);
}

static uint8_t input_report[64];
static uint8_t telemetry_report[58];
static uint8_t live_report[LIVE_REPORT_LEN];
static uint8_t scratch[64];
static uint32_t last_buttons;
uint8_t rumble_weak, rumble_strong;   /* usb_ds4.h; not driven to hardware yet (README) */
static uint16_t last_adc[KISHI_ADC_COUNT];

static void build_config_descriptor(void)
{
	const uint8_t d[CONFIG_LEN] = {
		/* configuration: 1 interface, bus powered, 100 mA */
		9, TUSB_DESC_CONFIGURATION, CONFIG_LEN, 0, 1, 1, 0, 0x80, 50,
		/* interface 0: HID, 2 endpoints, no boot protocol */
		9, TUSB_DESC_INTERFACE, 0, 0, 2, TUSB_CLASS_HID, 0, 0, 0,
		/* HID 1.11, one report descriptor */
		9, HID_DESC_TYPE_HID, 0x11, 0x01, 0, 1, HID_DESC_TYPE_REPORT,
		(uint8_t)(ds4_report_descriptor_len & 0xFF), (uint8_t)(ds4_report_descriptor_len >> 8),
		7, TUSB_DESC_ENDPOINT, EP_IN, TUSB_XFER_INTERRUPT, EP_IN_SIZE, 0, 5,
		7, TUSB_DESC_ENDPOINT, EP_OUT, TUSB_XFER_INTERRUPT, EP_OUT_SIZE, 0, 5,
	};

	memcpy(config_descriptor, d, sizeof(d));
}

static void put32(uint8_t *p, uint32_t v)
{
	p[0] = (uint8_t)v;
	p[1] = (uint8_t)(v >> 8);
	p[2] = (uint8_t)(v >> 16);
	p[3] = (uint8_t)(v >> 24);
}

static void fill_telemetry(void)
{
	uint8_t *t = telemetry_report;
	unsigned i;

	memset(t, 0, sizeof(telemetry_report));
	t[0] = 0xAB;
	t[1] = 1;                                   /* telemetry version (V1 format) */
	t[2] = (uint8_t)((tud_mounted() ? 1 : 0) | (kcfg_from_image ? 2 : 0) | live_telemetry_flags() | TELE_BOARD_V2PRO);
	t[3] = last_buttons & 0xFF;
	t[4] = last_buttons >> 8;
	for (i = 0; i < KISHI_ADC_COUNT; i++) {
		t[5 + 2 * i] = last_adc[i] & 0xFF;
		t[6 + 2 * i] = last_adc[i] >> 8;
	}
	for (i = 0; i < 4; i++) {
		t[17 + i] = (uint8_t)(kcfg_active_crc >> (8 * i));
		t[21 + i] = (uint8_t)(kcfg_persisted_crc >> (8 * i));
	}
	t[25] = LIVE_PROTOCOL;
	/*
	 * V2 Pro extras after the V1 fields: [26..28] waits that timed out (diag.h, 24 bits), [29] button bits 16..23 (M1,
	 * M2; read by the KishiDS app when flag TELE_BOARD_V2PRO is set), then the CPU cycle count at each start-up stage
	 * (diag_stamp), read by tools/v2pro_probe.py boottime; [54..55] the last rumble levels the host sent (weak, strong).
	 */
	put32(&t[26], diag.timeouts & 0x00FFFFFFu);
	t[29] = (uint8_t)(last_buttons >> 16);
	for (i = 0; i < 6; i++) {
		static const uint8_t stages[6] = {DS_COMBO, DS_CLOCK, DS_BOARD, DS_CONFIG, DS_USB_INIT, DS_CONFIGURED};

		put32(&t[30 + 4 * i], diag_stamp[stages[i]]);
	}
	t[54] = rumble_weak;
	t[55] = rumble_strong;
	t[56] = haptics_status[0];   /* DRV2605 STATUS per side (0xFF: no answer) */
	t[57] = haptics_status[1];
}

void usb_ds4_init(void)
{
	memcpy(usb_string_text[0], kcfg.manufacturer, 32);
	memcpy(usb_string_text[1], kcfg.product, 32);
	identity_from_uid(usb_string_text[2]);
	usb_string_text[0][31] = usb_string_text[1][31] = usb_string_text[2][31] = 0;
	build_config_descriptor();

	clock_usb0_init();
	{
		const tusb_rhport_init_t dev = {.role = TUSB_ROLE_DEVICE, .speed = TUSB_SPEED_FULL};

		tusb_rhport_init(0, &dev);
	}
}

void usb_ds4_task(void)
{
	tud_task();
}

int usb_ds4_configured(void)
{
	return tud_mounted() ? 1 : 0;
}

int usb_ds4_send(const uint8_t report[64])
{
	if (!tud_mounted() || !tud_hid_ready()) {
		return -1;
	}
	memcpy(input_report, report, sizeof(input_report));
	/* TinyUSB prepends the report ID itself. */
	return tud_hid_report(report[0], report + 1, sizeof(input_report) - 1) ? 0 : -1;
}

void usb_ds4_set_telemetry(uint32_t buttons, const uint16_t adc[KISHI_ADC_COUNT])
{
	last_buttons = buttons;
	memcpy(last_adc, adc, sizeof(last_adc));
}

/* ---- TinyUSB callbacks ---- */

#define USBLOG_COUNT(f) do { if (usblog.f < 255u) usblog.f++; } while (0)

void tud_mount_cb(void)
{
	USBLOG_COUNT(n_mount);
}

void tud_suspend_cb(bool remote_wakeup_en)
{
	(void)remote_wakeup_en;
	USBLOG_COUNT(n_suspend);
}

uint8_t const *tud_descriptor_device_cb(void)
{
	USBLOG_COUNT(n_dev);
	return (uint8_t const *)&device_descriptor;
}

uint8_t const *tud_descriptor_configuration_cb(uint8_t index)
{
	(void)index;
	USBLOG_COUNT(n_cfg);
	return config_descriptor;
}

uint16_t const *tud_descriptor_string_cb(uint8_t index, uint16_t langid)
{
	const char *s;
	unsigned n = 0;

	(void)langid;
	USBLOG_COUNT(n_str);
	if (index < 16) {
		usblog.str_mask |= (uint16_t)(1u << index);
	}
	if (index == 0) {
		string_buf[1] = 0x0409;
		n = 1;
	} else if (index <= 3) {
		s = usb_string_text[index - 1];
		while (n < 31 && s[n]) {
			string_buf[1 + n] = (uint8_t)s[n];
			n++;
		}
	} else {
		return NULL;
	}
	string_buf[0] = (uint16_t)((TUSB_DESC_STRING << 8) | (2 * n + 2));
	return string_buf;
}

uint8_t const *tud_hid_descriptor_report_cb(uint8_t instance)
{
	(void)instance;
	USBLOG_COUNT(n_rep);
	return ds4_report_descriptor;
}

/* TinyUSB has already written the report ID into the reply; we fill the bytes after it. */
static uint16_t copy_without_id(const uint8_t *with_id, uint16_t len, uint8_t *buffer, uint16_t reqlen)
{
	uint16_t n = len > 1 ? (uint16_t)(len - 1) : 0;

	if (n > reqlen) {
		n = reqlen;
	}
	memcpy(buffer, with_id + 1, n);
	return n;
}

uint16_t tud_hid_get_report_cb(uint8_t instance, uint8_t report_id, hid_report_type_t report_type, uint8_t *buffer,
			       uint16_t reqlen)
{
	const uint8_t *data;
	uint16_t len;

	(void)instance;
	(void)report_type;
	usblog_get_report(report_id);
	switch (report_id) {
	case 0x01:
		return copy_without_id(input_report, sizeof(input_report), buffer, reqlen);
	case 0xAB:
		fill_telemetry();
		return copy_without_id(telemetry_report, sizeof(telemetry_report), buffer, reqlen);
	case LIVE_REPORT_ID:
		live_get_report(live_report);
		return copy_without_id(live_report, sizeof(live_report), buffer, reqlen);
	default:
		len = ds4_fixed_feature(report_id, scratch, &data);
		return len ? copy_without_id(data, len, buffer, reqlen) : 0;
	}
}

/*
 * DS4 output report 0x05 (rumble and lightbar).  From the interrupt OUT endpoint TinyUSB passes report_id 0 with the
 * ID still in buffer[0]; from a control SET_REPORT it passes the ID and strips it.  After the ID: [0] valid flags
 * (bit 0 rumble, bit 1 lightbar), [3] weak motor, [4] strong motor, [5..7] lightbar R, G, B.
 */
static void ds4_output_report(uint8_t report_id, uint8_t const *buf, uint16_t len)
{
	if (report_id == 0 && len > 0 && buf[0] == 0x05) {
		report_id = 0x05;
		buf++;
		len--;
	}
	if (report_id != 0x05 || len < 8) {
		return;
	}
	USBLOG_COUNT(n_out);
	if (buf[0] & 0x02) {
		led_set_lightbar(buf[5], buf[6], buf[7]);
		memcpy(usblog.rgb, &buf[5], 3);
	}
	if (buf[0] & 0x01) {
		rumble_weak = buf[3];
		rumble_strong = buf[4];
		usblog.rumble[0] = buf[3];
		usblog.rumble[1] = buf[4];
	}
}

void tud_hid_set_report_cb(uint8_t instance, uint8_t report_id, hid_report_type_t report_type, uint8_t const *buffer,
			   uint16_t bufsize)
{
	uint8_t with_id[64];

	(void)instance;
	if (report_type == HID_REPORT_TYPE_OUTPUT) {
		ds4_output_report(report_id, buffer, bufsize);
		return;
	}
	if (report_type == HID_REPORT_TYPE_FEATURE) {
		usblog_set_report(report_id);
	}
	/* Other feature writes are accepted and ignored, as on the V1. */
	if (report_type != HID_REPORT_TYPE_FEATURE || report_id != LIVE_REPORT_ID || bufsize > sizeof(with_id) - 1) {
		return;
	}
	/* live.c expects the report ID as the first byte, which TinyUSB has stripped. */
	with_id[0] = report_id;
	memcpy(with_id + 1, buffer, bufsize);
	live_set_report(with_id, bufsize + 1u);
}

void USB0_IRQHandler(void);
void USB0_IRQHandler(void)
{
	diag.usb_irqs++;
	DIAG_STAGE(DS_USB_IRQ);
	tud_int_handler(0);
}
