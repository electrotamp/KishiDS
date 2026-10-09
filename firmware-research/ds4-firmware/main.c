/*
 * Kishi 0290 (Razer Kishi V1) DualShock 4-style USB firmware.
 *
 * Linked at the vendor bootloader's application base 0x08003000 (see
 * stm32f072_kishi_app3000.ld).  Reads the real Kishi controls (pin map and
 * calibration recovered from stock v2.70, see ../BOARD_MAP.md), applies the
 * settings in the config block (config.h) and reports them as a wired DualShock 4.
 * Build with DIAG=1 for the diagnostic variant.
 */

#include <stddef.h>
#include <string.h>
#include <stdint.h>

#include <libopencm3/cm3/systick.h>
#include <libopencm3/stm32/rcc.h>
#include <libopencm3/stm32/flash.h>
#include <libopencm3/stm32/crs.h>
#include <libopencm3/stm32/gpio.h>
#include <libopencm3/usb/usbd.h>
#include <libopencm3/usb/hid.h>

#include "config.h"
#include "diag.h"
#include "ds4_usb.h"
#include "kishi_io.h"
#include "led.h"
#include "live.h"
#include "report.h"

#define REG32(a) (*(volatile uint32_t *)(a))
#define USB_CNTR_REG  REG32(0x40005C40)
#define USB_BCDR_REG  REG32(0x40005C58)
#define USB_CNTR_FRES 0x0001u

/* Stock keeps its settings/calibration struct (0x59 bytes) in this flash page; we only read it. */
#define STOCK_CAL_PAGE ((const uint8_t *)0x0800F800)

static usbd_device *usb_device;
static struct kishi_cal cal;

/* Latest samples, for the host-visible telemetry report. */
static uint16_t last_buttons;
static uint16_t last_adc[KISHI_ADC_COUNT];

/* Captured USB descriptor from the wired CUH-ZCT1U DS4 CTS profile. */
static struct usb_device_descriptor device_descriptor = {
	.bLength = USB_DT_DEVICE_SIZE,
	.bDescriptorType = USB_DT_DEVICE,
	.bcdUSB = 0x0200,
	.bDeviceClass = 0,
	.bDeviceSubClass = 0,
	.bDeviceProtocol = 0,
	.bMaxPacketSize0 = 64,
	.idVendor = 0x054C,
	.idProduct = 0x05C4,
	.bcdDevice = 0x0100,
	.iManufacturer = 1,
	.iProduct = 2,
	.iSerialNumber = 3,
	.bNumConfigurations = 1,
};

static const struct {
	struct usb_hid_descriptor hid;
	uint8_t report_type;
	uint16_t report_length;
} __attribute__((packed)) hid_function = {
	.hid = {
		.bLength = sizeof(hid_function),
		.bDescriptorType = USB_HID_DT_HID,
		.bcdHID = 0x0111,
		.bCountryCode = 0,
		.bNumDescriptors = 1,
	},
	.report_type = USB_HID_DT_REPORT,
	.report_length = DS4_REPORT_DESCRIPTOR_LEN,
};

static struct usb_endpoint_descriptor endpoints[] = {{
	.bLength = USB_DT_ENDPOINT_SIZE,
	.bDescriptorType = USB_DT_ENDPOINT,
	.bEndpointAddress = 0x81,
	.bmAttributes = USB_ENDPOINT_ATTR_INTERRUPT,
	.wMaxPacketSize = 64,
	.bInterval = 5,
}, {
	.bLength = USB_DT_ENDPOINT_SIZE,
	.bDescriptorType = USB_DT_ENDPOINT,
	.bEndpointAddress = 0x01,
	.bmAttributes = USB_ENDPOINT_ATTR_INTERRUPT,
	.wMaxPacketSize = 32,
	.bInterval = 5,
}};

static const struct usb_interface_descriptor hid_interface[] = {{
	.bLength = USB_DT_INTERFACE_SIZE,
	.bDescriptorType = USB_DT_INTERFACE,
	.bInterfaceNumber = 0,
	.bAlternateSetting = 0,
	.bNumEndpoints = 2,
	.bInterfaceClass = USB_CLASS_HID,
	.bInterfaceSubClass = USB_HID_SUBCLASS_NO,
	.bInterfaceProtocol = USB_HID_INTERFACE_PROTOCOL_NONE,
	.iInterface = 0,
	.endpoint = endpoints,
	.extra = &hid_function,
	.extralen = sizeof(hid_function),
}};

static const struct usb_interface interfaces[] = {{
	.num_altsetting = 1,
	.altsetting = hid_interface,
}};

static const struct usb_config_descriptor configuration_descriptor = {
	.bLength = USB_DT_CONFIGURATION_SIZE,
	.bDescriptorType = USB_DT_CONFIGURATION,
	.wTotalLength = 0,
	.bNumInterfaces = 1,
	.bConfigurationValue = 1,
	.iConfiguration = 0,
	.bmAttributes = 0x80,
	.bMaxPower = 50,
	.interface = interfaces,
};

/* USB strings are fixed at enumeration; copied so a live config change cannot alter them mid-session. */
static char usb_string_text[3][32];
static const char *usb_strings[3];

/* ID 0x01 plus the 63-byte DS4 input payload, rebuilt every loop. */
static uint8_t input_report[KISHI_REPORT_SIZE];

/* Telemetry for the KishiDS app (feature report 0xAB): buttons, raw ADC, active config CRC. */
static uint8_t telemetry_report[58];

/* Live-editing status (feature report 0xAC, see live.h). */
#ifndef DIAG
static uint8_t live_report[LIVE_REPORT_LEN];
#endif

/* Scratch for the all-zero feature replies (ds4_fixed_feature). */
static uint8_t generic_report[64];

static uint8_t control_buffer[512];
static uint8_t discard_buffer[64];

static volatile uint8_t configured;

static void fill_telemetry(void)
{
	uint8_t *t = telemetry_report;
	unsigned i;

	for (i = 0; i < sizeof(telemetry_report); i++) {
		t[i] = 0;
	}
	t[0] = 0xAB;
	t[1] = 1;                                   /* telemetry version */
	t[2] = (uint8_t)((configured ? 1 : 0) | (kcfg_from_image ? 2 : 0) | live_telemetry_flags());
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
}

static enum usbd_request_return_codes hid_control_request(
	usbd_device *dev, struct usb_setup_data *req, uint8_t **buf,
	uint16_t *len, void (**complete)(usbd_device *dev, struct usb_setup_data *req))
{
	uint8_t report_id;

	(void)dev;
	(void)complete;
	diag_mark(DM_CLASS_REQ, 0xC7C7);

	if (req->bmRequestType == 0x81 && req->bRequest == USB_REQ_GET_DESCRIPTOR &&
		(req->wValue >> 8) == USB_HID_DT_REPORT) {
		*buf = (uint8_t *)ds4_report_descriptor;
		*len = DS4_REPORT_DESCRIPTOR_LEN;
		return USBD_REQ_HANDLED;
	}

	if ((req->bmRequestType & 0x7F) != 0x21) {
		return USBD_REQ_NOTSUPP;
	}

	report_id = req->wValue & 0xFF;
#ifndef DIAG
	if (req->bRequest == USB_HID_REQ_TYPE_SET_REPORT && report_id == LIVE_REPORT_ID) {
		live_set_report(*buf, *len);
		return USBD_REQ_HANDLED;
	}
#endif
	if (req->bRequest == USB_HID_REQ_TYPE_SET_REPORT && report_id == 0xF2) {
		diag_set_report(*buf, *len);   /* DIAG builds only: select the memory-dump address */
	}
	if (req->bRequest == USB_HID_REQ_TYPE_SET_REPORT ||
		req->bRequest == USB_HID_REQ_TYPE_SET_IDLE ||
		req->bRequest == USB_HID_REQ_TYPE_SET_PROTOCOL) {
		return USBD_REQ_HANDLED;
	}

	if (req->bRequest != USB_HID_REQ_TYPE_GET_REPORT) {
		return USBD_REQ_NOTSUPP;
	}

	if (diag_feature_report(report_id, buf, len)) {
		return USBD_REQ_HANDLED;
	}

	switch (report_id) {
#ifndef DIAG
	case LIVE_REPORT_ID:
		live_get_report(live_report);
		*buf = live_report;
		*len = LIVE_REPORT_LEN;
		return USBD_REQ_HANDLED;
#endif
	case 0x01:
		*buf = input_report;
		*len = sizeof(input_report);
		return USBD_REQ_HANDLED;
	case 0xAB:
		fill_telemetry();
		*buf = telemetry_report;
		*len = sizeof(telemetry_report);
		return USBD_REQ_HANDLED;
	default: {
		/* The fixed DS4 feature reports (0x02, 0x12, 0x81, 0xA3, and zeros for the other declared IDs). */
		const uint8_t *data;
		uint16_t n = ds4_fixed_feature(report_id, generic_report, &data);

		if (n == 0) {
			return USBD_REQ_NOTSUPP;
		}
		*buf = (uint8_t *)data;
		*len = n;
		return USBD_REQ_HANDLED;
	}
	}
}

static void output_report_received(usbd_device *dev, uint8_t ep)
{
	(void)ep;
	(void)usbd_ep_read_packet(dev, 0x01, discard_buffer, sizeof(discard_buffer));
}

/*
 * Endpoint 1's buffer descriptor lives in USB packet memory, which is plain
 * SRAM: after a power-on it holds random data until usbd_ep_setup() programs
 * it.  Writing to endpoint 0x81 before the host has configured the device
 * therefore copies through a garbage pointer and hangs on a cold start.  Only
 * touch it once SET_CONFIGURATION has run (and stop again on bus reset).
 */
static void usb_reset_cb(void)
{
	configured = 0;
	diag_mark(DM_BUS_RESET, 0xE5E7);
}

static void set_configuration(usbd_device *dev, uint16_t value)
{
	configured = (value != 0);
	diag_mark(DM_SET_CONFIG, 0xC0FF);
	usbd_ep_setup(dev, 0x81, USB_ENDPOINT_ATTR_INTERRUPT, 64, NULL);
	usbd_ep_setup(dev, 0x01, USB_ENDPOINT_ATTR_INTERRUPT, 32, output_report_received);
	usbd_register_control_callback(
		dev,
		USB_REQ_TYPE_STANDARD | USB_REQ_TYPE_INTERFACE,
		USB_REQ_TYPE_TYPE | USB_REQ_TYPE_RECIPIENT,
		hid_control_request);
	usbd_register_control_callback(
		dev,
		USB_REQ_TYPE_CLASS | USB_REQ_TYPE_INTERFACE,
		USB_REQ_TYPE_TYPE | USB_REQ_TYPE_RECIPIENT,
		hid_control_request);
}

/*
 * Clock setup, mirroring stock v2.70 SystemClock_Config (image offset 0x59BC):
 * HSI48 on, PLL fed from HSI48 (/2 x2) = 48 MHz as SYSCLK, flash latency 1,
 * USB clocked from HSI48.  Stock never enables the HSE crystal (the "12 MHz"
 * constant in its HAL is just a leftover default), and it must not depend on
 * what the vendor bootloader left running, so we reconfigure from scratch and
 * additionally trim HSI48 from the USB SOF via CRS.
 */
static void clock_setup(void)
{
	/* Run from HSI48 directly while the PLL is (re)configured. */
	rcc_osc_on(RCC_HSI48);
	rcc_wait_for_osc_ready(RCC_HSI48);
	flash_prefetch_enable();
	flash_set_ws(FLASH_ACR_LATENCY_024_048MHZ);
	rcc_set_sysclk_source(RCC_HSI48);
	rcc_osc_off(RCC_PLL);
	while (rcc_is_osc_ready(RCC_PLL)) {
	}
	rcc_set_hpre(RCC_CFGR_HPRE_NODIV);
	rcc_set_ppre(RCC_CFGR_PPRE_NODIV);

	RCC_CFGR2 = (RCC_CFGR2 & ~RCC_CFGR2_PREDIV) | RCC_CFGR2_PREDIV_DIV2;
	RCC_CFGR = (RCC_CFGR & ~(RCC_CFGR_PLLSRC | RCC_CFGR_PLLSRC0 | (0xFu << RCC_CFGR_PLLMUL_SHIFT))) |
		RCC_CFGR_PLLSRC | RCC_CFGR_PLLSRC0 | RCC_CFGR_PLLMUL_MUL2;
	rcc_osc_on(RCC_PLL);
	rcc_wait_for_osc_ready(RCC_PLL);
	rcc_set_sysclk_source(RCC_PLL);

	RCC_CFGR3 &= ~RCC_CFGR3_USBSW;  /* USB from HSI48 */
	crs_autotrim_usb_enable();
	rcc_apb1_frequency = 48000000;
	rcc_ahb_frequency = 48000000;
}

static void usb_setup(void)
{
	volatile uint32_t i;

	/* USB identity from the config block. */
	device_descriptor.idVendor = kcfg.vid;
	device_descriptor.idProduct = kcfg.pid;
	device_descriptor.bcdDevice = kcfg.bcd_device;
	endpoints[0].bInterval = kcfg.poll_ms;
	endpoints[1].bInterval = kcfg.poll_ms;
	memcpy(usb_string_text[0], kcfg.manufacturer, 32);
	memcpy(usb_string_text[1], kcfg.product, 32);
	kcfg_resolve_serial((char *)usb_string_text[2], (const uint8_t *)KCFG_FACTORY_SERIAL_ADDR,
		(const uint32_t *)KCFG_UID_ADDR);
	{
		/* The DS4 Bluetooth address (features 0x12/0x81) from the chip's 96-bit UID: iOS remembers a DS4 by it and
		 * takes two controllers with the same one for one (ds4_usb.h).  Folded to 48 bits, unicast, locally
		 * administered, as the V2 Pro does with its UUID. */
		const uint8_t *uid = (const uint8_t *)KCFG_UID_ADDR;
		uint8_t addr[6];
		unsigned k;

		for (k = 0; k < 6; k++) {
			addr[k] = (uint8_t)(uid[k] ^ uid[k + 6]);
		}
		addr[5] = (uint8_t)((addr[5] & 0xFC) | 0x02);
		ds4_set_device_address(addr);
	}
	usb_strings[0] = usb_string_text[0];
	usb_strings[1] = usb_string_text[1];
	usb_strings[2] = usb_string_text[2];

	/* PA11/PA12 are USB D-/D+ on STM32F072, alternate function 0. */
	rcc_periph_clock_enable(RCC_GPIOA);
	rcc_periph_clock_enable(RCC_USB);
	gpio_mode_setup(GPIOA, GPIO_MODE_AF, GPIO_PUPD_NONE, GPIO11 | GPIO12);
	gpio_set_af(GPIOA, 0, GPIO11 | GPIO12);

	/*
	 * RM0091 USB bring-up: D+ pull-up off, release power-down while holding the
	 * peripheral in reset, wait out the analog start-up time, then attach.
	 */
	USB_BCDR_REG = 0;
	USB_CNTR_REG = USB_CNTR_FRES;
	for (i = 0; i < 20000; i++) {
	}
	USB_CNTR_REG = 0;

	usb_device = usbd_init(&st_usbfs_v2_usb_driver, &device_descriptor,
		&configuration_descriptor, usb_strings, 3, control_buffer,
		sizeof(control_buffer));
	usbd_register_set_config_callback(usb_device, set_configuration);
	usbd_register_reset_callback(usb_device, usb_reset_cb);
}

/* Calibration: the config block's when selected, else stock's settings page (config values as fallback). */
static void cal_load(void)
{
	if (kcfg.calib_mode == 1 || !report_cal_from_stock_page(&cal, STOCK_CAL_PAGE)) {
		report_cal_from_config(&cal, &kcfg);
	}
}

/* Called by live editing after a new config became active: re-derive what main caches from it. */
void hw_apply_config(void)
{
	cal_load();
}

/* Free-running millisecond counter from SysTick (polled, no interrupt). */
static void ms_timer_init(void)
{
	systick_set_clocksource(STK_CSR_CLKSOURCE_AHB);
	systick_set_reload(48000 - 1);
	systick_clear();
	systick_counter_enable();
}

int main(void)
{
	uint16_t adc[KISHI_ADC_COUNT];
	uint16_t buttons;
	uint32_t ms = 0;
	uint8_t counter = 0;

	diag_boot();
	kcfg_load();
	live_init();
	clock_setup();
	diag_after_clock();
	cal_load();
	diag_mark(DM_CAL_DONE, 0xCA11);
	kishi_io_init();
	led_init();
	ms_timer_init();
	diag_mark(DM_IO_DONE, 0xA001);
	usb_setup();
	diag_after_usb_setup();

	while (1) {
		diag_loop();
		usbd_poll(usb_device);
		live_service();
		diag_mark(DM_FIRST_POLL, 0x9011);
		if (systick_get_countflag()) {
			ms++;
		}
		kishi_read_adc(adc);
		diag_mark(DM_FIRST_ADC_PASS, 0xAD0F);
		buttons = kishi_read_buttons();
		diag_mark(DM_BUTTONS, 0xB070);
		last_buttons = buttons;
		for (unsigned i = 0; i < KISHI_ADC_COUNT; i++) {
			last_adc[i] = adc[i];
		}
		report_build(&kcfg, &cal, buttons, adc, counter++, input_report);
		diag_fill_report(input_report, buttons, adc);
		diag_mark(DM_REPORT_BUILT, 0xB011);
		led_update(ms, configured);
		if (configured) {
			(void)usbd_ep_write_packet(usb_device, 0x81, input_report,
				sizeof(input_report));
		}
		diag_mark(DM_EP_STEP, 0xE9E9);
	}
}
