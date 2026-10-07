/*
 * Kishi 0290 stage 2: DualShock 4-style USB device driven by the real Kishi
 * controls.  Linked at the vendor application base 0x08003000 (see
 * stm32f072_kishi_app3000.ld).  With -DDIAG, raw ADC / button mask are
 * embedded in unused DS4 report bytes and the stock calibration page is
 * readable through feature reports 0xF1/0xB0.  Build with -DDIAG for that;
 * the default (release) build sends a clean DS4 report.
 */

#include <stddef.h>
#include <stdint.h>

#include <libopencm3/stm32/rcc.h>
#include <libopencm3/stm32/flash.h>
#include <libopencm3/stm32/crs.h>
#include <libopencm3/stm32/gpio.h>
#include <libopencm3/usb/usbd.h>
#include <libopencm3/usb/hid.h>

#include "kishi_io.h"

#define REG32(a) (*(volatile uint32_t *)(a))
#define USB_CNTR_REG  REG32(0x40005C40)
#define USB_ISTR_REG  REG32(0x40005C44)
#define USB_DADDR_REG REG32(0x40005C4C)
#define USB_BCDR_REG  REG32(0x40005C58)
#define USB_CNTR_FRES 0x0001u

static usbd_device *usb_device;

/* Captured USB descriptor from the wired CUH-ZCT1U DS4 CTS profile. */
static const uint8_t ds4_report_descriptor[] = {
	0x05, 0x01, 0x09, 0x05, 0xA1, 0x01, 0x85, 0x01, 0x09, 0x30, 0x09, 0x31, 0x09, 0x32, 0x09, 0x35,
	0x15, 0x00, 0x26, 0xFF, 0x00, 0x75, 0x08, 0x95, 0x04, 0x81, 0x02, 0x09, 0x39, 0x15, 0x00, 0x25,
	0x07, 0x35, 0x00, 0x46, 0x3B, 0x01, 0x65, 0x14, 0x75, 0x04, 0x95, 0x01, 0x81, 0x42, 0x65, 0x00,
	0x05, 0x09, 0x19, 0x01, 0x29, 0x0E, 0x15, 0x00, 0x25, 0x01, 0x75, 0x01, 0x95, 0x0E, 0x81, 0x02,
	0x06, 0x00, 0xFF, 0x09, 0x20, 0x75, 0x06, 0x95, 0x01, 0x15, 0x00, 0x25, 0x7F, 0x81, 0x02, 0x05,
	0x01, 0x09, 0x33, 0x09, 0x34, 0x15, 0x00, 0x26, 0xFF, 0x00, 0x75, 0x08, 0x95, 0x02, 0x81, 0x02,
	0x06, 0x00, 0xFF, 0x09, 0x21, 0x95, 0x36, 0x81, 0x02, 0x85, 0x05, 0x09, 0x22, 0x95, 0x1F, 0x91,
	0x02, 0x85, 0x04, 0x09, 0x23, 0x95, 0x24, 0xB1, 0x02, 0x85, 0x02, 0x09, 0x24, 0x95, 0x24, 0xB1,
	0x02, 0x85, 0x08, 0x09, 0x25, 0x95, 0x03, 0xB1, 0x02, 0x85, 0x10, 0x09, 0x26, 0x95, 0x04, 0xB1,
	0x02, 0x85, 0x11, 0x09, 0x27, 0x95, 0x02, 0xB1, 0x02, 0x85, 0x12, 0x06, 0x02, 0xFF, 0x09, 0x21,
	0x95, 0x0F, 0xB1, 0x02, 0x85, 0x13, 0x09, 0x22, 0x95, 0x16, 0xB1, 0x02, 0x85, 0x14, 0x06, 0x05,
	0xFF, 0x09, 0x20, 0x95, 0x10, 0xB1, 0x02, 0x85, 0x15, 0x09, 0x21, 0x95, 0x2C, 0xB1, 0x02, 0x06,
	0x80, 0xFF, 0x85, 0x80, 0x09, 0x20, 0x95, 0x06, 0xB1, 0x02, 0x85, 0x81, 0x09, 0x21, 0x95, 0x06,
	0xB1, 0x02, 0x85, 0x82, 0x09, 0x22, 0x95, 0x05, 0xB1, 0x02, 0x85, 0x83, 0x09, 0x23, 0x95, 0x01,
	0xB1, 0x02, 0x85, 0x84, 0x09, 0x24, 0x95, 0x04, 0xB1, 0x02, 0x85, 0x85, 0x09, 0x25, 0x95, 0x06,
	0xB1, 0x02, 0x85, 0x86, 0x09, 0x26, 0x95, 0x06, 0xB1, 0x02, 0x85, 0x87, 0x09, 0x27, 0x95, 0x23,
	0xB1, 0x02, 0x85, 0x88, 0x09, 0x28, 0x95, 0x22, 0xB1, 0x02, 0x85, 0x89, 0x09, 0x29, 0x95, 0x02,
	0xB1, 0x02, 0x85, 0x90, 0x09, 0x30, 0x95, 0x05, 0xB1, 0x02, 0x85, 0x91, 0x09, 0x31, 0x95, 0x03,
	0xB1, 0x02, 0x85, 0x92, 0x09, 0x32, 0x95, 0x03, 0xB1, 0x02, 0x85, 0x93, 0x09, 0x33, 0x95, 0x0C,
	0xB1, 0x02, 0x85, 0xA0, 0x09, 0x40, 0x95, 0x06, 0xB1, 0x02, 0x85, 0xA1, 0x09, 0x41, 0x95, 0x01,
	0xB1, 0x02, 0x85, 0xA2, 0x09, 0x42, 0x95, 0x01, 0xB1, 0x02, 0x85, 0xA3, 0x09, 0x43, 0x95, 0x30,
	0xB1, 0x02, 0x85, 0xA4, 0x09, 0x44, 0x95, 0x0D, 0xB1, 0x02, 0x85, 0xA5, 0x09, 0x45, 0x95, 0x15,
	0xB1, 0x02, 0x85, 0xA6, 0x09, 0x46, 0x95, 0x15, 0xB1, 0x02, 0x85, 0xF0, 0x09, 0x47, 0x95, 0x3F,
	0xB1, 0x02, 0x85, 0xF1, 0x09, 0x48, 0x95, 0x3F, 0xB1, 0x02, 0x85, 0xF2, 0x09, 0x49, 0x95, 0x0F,
	0xB1, 0x02, 0x85, 0xA7, 0x09, 0x4A, 0x95, 0x01, 0xB1, 0x02, 0x85, 0xA8, 0x09, 0x4B, 0x95, 0x01,
	0xB1, 0x02, 0x85, 0xA9, 0x09, 0x4C, 0x95, 0x08, 0xB1, 0x02, 0x85, 0xAA, 0x09, 0x4E, 0x95, 0x01,
	0xB1, 0x02, 0x85, 0xAB, 0x09, 0x4F, 0x95, 0x39, 0xB1, 0x02, 0x85, 0xAC, 0x09, 0x50, 0x95, 0x39,
	0xB1, 0x02, 0x85, 0xAD, 0x09, 0x51, 0x95, 0x0B, 0xB1, 0x02, 0x85, 0xAE, 0x09, 0x52, 0x95, 0x01,
	0xB1, 0x02, 0x85, 0xAF, 0x09, 0x53, 0x95, 0x02, 0xB1, 0x02, 0x85, 0xB0, 0x09, 0x54, 0x95, 0x3F,
	0xB1, 0x02, 0x85, 0xB1, 0x09, 0x55, 0x95, 0x02, 0xB1, 0x02, 0x85, 0xB2, 0x09, 0x56, 0x95, 0x02,
	0xB1, 0x02, 0xC0,
};

static const struct usb_device_descriptor device_descriptor = {
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
	.report_length = sizeof(ds4_report_descriptor),
};

static const struct usb_endpoint_descriptor endpoints[] = {{
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

static const char *usb_strings[] = {
	"Sony Computer Entertainment",
	"Wireless Controller",
	"KISHI-DS4",
};

/* ID 0x01 plus the 63-byte DS4 input payload, rebuilt every loop. */
static uint8_t input_report[64];

/* Stock keeps its settings/calibration struct (0x59 bytes) in this flash page. */
#define STOCK_CAL_PAGE ((const uint8_t *)0x0800F800)
#ifdef DIAG
static uint8_t cal_report[64];
#endif

/* Captured baseline feature reports from the same wired DS4 profile. */
static const uint8_t feature_02[] = {
	0x02, 0xF8, 0xFF, 0xFD, 0xFF, 0xF9, 0xFF, 0xDF, 0x21, 0xF1,
	0xDD, 0x95, 0x22, 0x67, 0xDD, 0xF2, 0x23, 0x1C, 0xDC, 0x1C,
	0x02, 0x1C, 0x02, 0xAA, 0x1F, 0x56, 0xE0, 0xF7, 0x20, 0x08,
	0xDF, 0x0A, 0x20, 0xF7, 0xDF, 0x06, 0x00,
};
static const uint8_t feature_a3[] = {
	0xA3, 0x41, 0x70, 0x72, 0x20, 0x20, 0x38, 0x20, 0x32, 0x30,
	0x31, 0x34, 0x00, 0x00, 0x00, 0x00, 0x00, 0x30, 0x39, 0x3A,
	0x34, 0x36, 0x3A, 0x30, 0x36, 0x00, 0x00, 0x00, 0x00, 0x00,
	0x00, 0x00, 0x00, 0x00, 0x01, 0x00, 0x43, 0x03, 0x00, 0x00,
	0x00, 0x51, 0x00, 0x05, 0x00, 0x00, 0x80, 0x03, 0x00,
};
static const uint8_t feature_81[] = {0x81, 0x62, 0x97, 0xC9, 0x00, 0x00, 0x00};

static const uint8_t feature_12[16] = {
	0x12, 0x62, 0x97, 0xC9, 0x00, 0x00, 0x00, 0x08, 0x25, 0x00,
};

/* (report ID, payload length) for the descriptor's other feature reports. */
static const uint8_t feature_sizes[] = {
	0x04, 0x24, 0x08, 0x03, 0x10, 0x04, 0x11, 0x02, 0x13, 0x16, 0x14, 0x10, 0x15, 0x2C,
	0x80, 0x06, 0x82, 0x05, 0x83, 0x01, 0x84, 0x04, 0x85, 0x06, 0x86, 0x06, 0x87, 0x23,
	0x88, 0x22, 0x89, 0x02, 0x90, 0x05, 0x91, 0x03, 0x92, 0x03, 0x93, 0x0C, 0xA0, 0x06,
	0xA1, 0x01, 0xA2, 0x01, 0xA4, 0x0D, 0xA5, 0x15, 0xA6, 0x15, 0xF0, 0x3F, 0xF1, 0x3F,
	0xF2, 0x0F, 0xA7, 0x01, 0xA8, 0x01, 0xA9, 0x08, 0xAA, 0x01, 0xAB, 0x39, 0xAC, 0x39,
	0xAD, 0x0B, 0xAE, 0x01, 0xAF, 0x02, 0xB0, 0x3F, 0xB1, 0x02, 0xB2, 0x02,
};
static uint8_t generic_report[64];

#ifdef DIAG
/*
 * Black-box boot log in spare flash (page 0x0800E000; the stock calibration
 * page 0x0800F800 is never touched).  One 56-byte slot (28 half-words) per
 * boot; each milestone programs its own half-word once.  After a failed cold
 * boot, reflash a DIAG build and read the last three slots via feature
 * reports 0xF1/0xB0/0xAC (one slot each, newest first).
 */
#define LOG_PAGE   0x0800E000u
#define LOG_HW     28u
#define LOG_SLOTS  (2048u / (LOG_HW * 2u))
static volatile uint16_t *log_slot;

static volatile uint16_t *log_slot_at(unsigned n)
{
	return (volatile uint16_t *)(LOG_PAGE + n * LOG_HW * 2u);
}

static void log_init(void)
{
	unsigned i;

	for (i = 0; i < LOG_SLOTS; i++) {
		if (log_slot_at(i)[0] == 0xFFFF) {
			log_slot = log_slot_at(i);
			return;
		}
	}
	flash_unlock();
	flash_erase_page(LOG_PAGE);
	flash_lock();
	log_slot = log_slot_at(0);
}

static void logw(unsigned idx, uint16_t v)
{
	if (!log_slot || log_slot[idx] != 0xFFFF || v == 0xFFFF) {
		return;
	}
	flash_unlock();
	flash_program_half_word((uint32_t)&log_slot[idx], v);
	flash_lock();
}

/* Newest-first slot n (0 = newest); returns pointer or NULL. */
static const volatile uint16_t *log_recent(unsigned n)
{
	int last = -1;
	unsigned i;

	for (i = 0; i < LOG_SLOTS; i++) {
		if (log_slot_at(i)[0] != 0xFFFF) {
			last = (int)i;
		}
	}
	if (last < 0 || (int)n > last) {
		return NULL;
	}
	return log_slot_at((unsigned)last - n);
}

/* State inherited from the vendor bootloader (taken before any init) and final state. */
static uint32_t snap_entry[15];
static uint32_t snap_final[14];
static uint8_t snap_report[64];

static void snapshot_regs(uint32_t *o, int final)
{
	o[0] = RCC_CR;
	o[1] = RCC_CFGR;
	o[2] = RCC_CFGR2;
	o[3] = RCC_CFGR3;
	o[4] = RCC_APB1ENR;
	o[5] = RCC_AHBENR;
	o[6] = REG32(0x40006C00); /* CRS_CR */
	o[7] = REG32(0x40005C40); /* USB_CNTR */
	o[8] = REG32(0x40005C58); /* USB_BCDR */
	o[9] = REG32(0xE000E100); /* NVIC_ISER */
	o[10] = REG32(0x40010000); /* SYSCFG_CFGR1 */
	o[11] = REG32(0x40022000); /* FLASH_ACR */
	o[12] = REG32(0x48000000); /* GPIOA_MODER */
	o[13] = REG32(0x40021034); /* RCC_CR2 */
	if (!final) {
		o[14] = RCC_CSR;      /* reset-cause flags */
	}
}
#endif

static uint8_t control_buffer[512];
static uint8_t discard_buffer[64];

static enum usbd_request_return_codes hid_control_request(
	usbd_device *dev, struct usb_setup_data *req, uint8_t **buf,
	uint16_t *len, void (**complete)(usbd_device *dev, struct usb_setup_data *req))
{
	uint8_t report_id;

	(void)dev;
	(void)complete;
#ifdef DIAG
	logw(8, 0xC7C7);   /* HID class/interface request reached */
#endif

	if (req->bmRequestType == 0x81 && req->bRequest == USB_REQ_GET_DESCRIPTOR &&
		(req->wValue >> 8) == USB_HID_DT_REPORT) {
		*buf = (uint8_t *)ds4_report_descriptor;
		*len = sizeof(ds4_report_descriptor);
		return USBD_REQ_HANDLED;
	}

	if ((req->bmRequestType & 0x7F) != 0x21) {
		return USBD_REQ_NOTSUPP;
	}

	report_id = req->wValue & 0xFF;
	if (req->bRequest == USB_HID_REQ_TYPE_SET_REPORT ||
		req->bRequest == USB_HID_REQ_TYPE_SET_IDLE ||
		req->bRequest == USB_HID_REQ_TYPE_SET_PROTOCOL) {
		return USBD_REQ_HANDLED;
	}

	if (req->bRequest != USB_HID_REQ_TYPE_GET_REPORT) {
		return USBD_REQ_NOTSUPP;
	}

	switch (report_id) {
	case 0x01:
		*buf = input_report;
		*len = sizeof(input_report);
		return USBD_REQ_HANDLED;
#ifdef DIAG
	case 0xF1: /* DIAG: boot log, newest slot */
	case 0xB0: /* DIAG: boot log, previous slot */
	case 0xAC: { /* DIAG: boot log, slot before that */
		unsigned which = report_id == 0xF1 ? 0 : report_id == 0xB0 ? 1 : 2;
		const volatile uint16_t *sl = log_recent(which);

		for (unsigned i = 0; i < 64; i++) {
			cal_report[i] = 0xFF;
		}
		cal_report[0] = report_id;
		if (sl) {
			for (unsigned h = 0; h < LOG_HW; h++) {
				cal_report[1 + h * 2] = sl[h] & 0xFF;
				cal_report[2 + h * 2] = sl[h] >> 8;
			}
		}
		*buf = cal_report;
		*len = sizeof(cal_report);
		return USBD_REQ_HANDLED;
	}
#endif
#ifdef DIAG
	case 0xF0: /* DIAG: register snapshots at entry (15 words) */
	case 0xAB: /* DIAG: register snapshots after init (14 words) */
		snap_report[0] = report_id;
		for (unsigned i = 0; i < 63; i++) {
			snap_report[1 + i] = 0;
		}
		for (unsigned i = 0; i < (report_id == 0xF0 ? 15u : 14u) * 4; i++) {
			const uint8_t *src = (const uint8_t *)(report_id == 0xF0 ? snap_entry : snap_final);
			snap_report[1 + i] = src[i];
		}
		*buf = snap_report;
		*len = sizeof(snap_report);
		return USBD_REQ_HANDLED;
#endif
	case 0x12: /* pairing info: device MAC, 0x08 0x25 0x00, host MAC */
		*buf = (uint8_t *)feature_12;
		*len = sizeof(feature_12);
		return USBD_REQ_HANDLED;
	case 0x02:
		*buf = (uint8_t *)feature_02;
		*len = sizeof(feature_02);
		return USBD_REQ_HANDLED;
	case 0xA3:
		*buf = (uint8_t *)feature_a3;
		*len = sizeof(feature_a3);
		return USBD_REQ_HANDLED;
	case 0x81:
		*buf = (uint8_t *)feature_81;
		*len = sizeof(feature_81);
		return USBD_REQ_HANDLED;
	default: {
		/* Any other feature report declared by the descriptor: all zeros. */
		for (unsigned i = 0; i < sizeof(feature_sizes); i += 2) {
			if (feature_sizes[i] == report_id) {
				unsigned n = feature_sizes[i + 1] + 1u;
				for (unsigned j = 0; j < n; j++) {
					generic_report[j] = 0;
				}
				generic_report[0] = report_id;
				*buf = generic_report;
				*len = n;
				return USBD_REQ_HANDLED;
			}
		}
		return USBD_REQ_NOTSUPP;
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
 * therefore copies through a garbage pointer and faults on a cold start.
 * Only touch it once SET_CONFIGURATION has run (and stop again on bus reset).
 */
static volatile uint8_t configured;

static void usb_reset_cb(void)
{
	configured = 0;
#ifdef DIAG
	logw(6, 0xE5E7);   /* host bus reset seen */
#endif
}

static void set_configuration(usbd_device *dev, uint16_t value)
{
	configured = (value != 0);
#ifdef DIAG
	logw(9, 0xC0FF);   /* SET_CONFIGURATION seen */
#endif
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

#ifdef DIAG
static void trace_cb(unsigned code, unsigned val)
{
	switch (code) {
	case 1: logw(16, (uint16_t)val | 0x8000u); break;   /* ADC_CR at first start */
	case 2: logw(17, (uint16_t)val | 0x8000u); break;   /* ADC_ISR when EOC timed out */
	case 3: logw(18, 0xAD0E); break;                    /* first conversion done */
	default: break;
	}
}
#endif

static void delay_cycles(uint32_t n)
{
	for (volatile uint32_t i = 0; i < n; i++) {
	}
}

static void usb_setup(void)
{
	/* PA11/PA12 are USB D-/D+ on STM32F072, alternate function 0. */
	rcc_periph_clock_enable(RCC_GPIOA);
	rcc_periph_clock_enable(RCC_USB);
	gpio_mode_setup(GPIOA, GPIO_MODE_AF, GPIO_PUPD_NONE, GPIO11 | GPIO12);
	gpio_set_af(GPIOA, 0, GPIO11 | GPIO12);

	/*
	 * Conservative bring-up (RM0091 USB): keep the D+ pull-up off, release
	 * power-down while holding the peripheral in reset, wait out the analog
	 * start-up time, then let the host see a clean attach.
	 */
	USB_BCDR_REG = 0;
	USB_CNTR_REG = USB_CNTR_FRES;
	delay_cycles(20000);   /* >> tSTARTUP */
	USB_CNTR_REG = 0;

	usb_device = usbd_init(&st_usbfs_v2_usb_driver, &device_descriptor,
		&configuration_descriptor, usb_strings, 3, control_buffer,
		sizeof(control_buffer));
	usbd_register_set_config_callback(usb_device, set_configuration);
	usbd_register_reset_callback(usb_device, usb_reset_cb);
}

static uint8_t hat_from_buttons(uint16_t b)
{
	int up = !!(b & (1u << KB_UP));
	int down = !!(b & (1u << KB_DOWN));
	int left = !!(b & (1u << KB_LEFT));
	int right = !!(b & (1u << KB_RIGHT));

	/* Opposite directions cancel. */
	if (up && down) {
		up = down = 0;
	}
	if (left && right) {
		left = right = 0;
	}
	if (up) {
		return right ? 1 : left ? 7 : 0;
	}
	if (down) {
		return right ? 3 : left ? 5 : 4;
	}
	return right ? 2 : left ? 6 : 8;
}

/*
 * Calibration, recovered from stock v2.70 (scaling helpers at image offsets
 * 0x492C / 0x4952 / 0x49E2).  Stock stores a 0x59-byte struct at 0x0800F800
 * validated by a 32-bit magic at +0x55 (value + 0xD22F8CD7 == 0).  If the
 * page is valid we use it; otherwise we fall back to the values this unit had
 * when the page was dumped.
 */
struct cal {
	uint16_t smax[4], smin[4], scenter[4];  /* per ADC raw index 0,1,4,3 -> see below */
	uint16_t dead;
	uint16_t t_lo[2], t_hi[2];              /* L2 (raw5), R2 (raw2) */
	uint8_t t_thresh;
};

static struct cal cal;

static uint16_t cal_u16(const uint8_t *page, unsigned off)
{
	return (uint16_t)(page[off] | (page[off + 1] << 8));
}

static const uint8_t default_cal_page[0x59] = {
	0xc0, 0x0c, 0x36, 0x02, 0xf5, 0x0c, 0x2e, 0x02, 0x43, 0x0d, 0xce, 0x02, 0x20, 0x0d, 0x52, 0x02,
	0xc1, 0x07, 0xcb, 0x01, 0x5e, 0x07, 0xa6, 0x01, 0x6e, 0x00, 0x0a, 0x0a, 0x78, 0x00, 0xb0, 0xff,
	0x78, 0x00, 0xb0, 0xff, [0x34] = 0xf4, 0x07, 0xb3, 0x07, 0x71, 0x07, 0xd0, 0x07, 0x66, 0x66, 0x66, 0x3f,
	[0x55] = 0x29, 0x73, 0xd0, 0x2d,
};

static void cal_load(void)
{
	const uint8_t *page = STOCK_CAL_PAGE;
	uint32_t magic = (uint32_t)page[0x55] | ((uint32_t)page[0x56] << 8) |
		((uint32_t)page[0x57] << 16) | ((uint32_t)page[0x58] << 24);

	if ((uint32_t)(magic + 0xD22F8CD7u) != 0) {
		page = default_cal_page;
	}

	/* Index = position in the stock ADC raw[] array (ch1,2,3,5,6,8). */
	cal.smax[0] = cal_u16(page, 0x00); cal.smin[0] = cal_u16(page, 0x02); cal.scenter[0] = cal_u16(page, 0x38); /* right X */
	cal.smax[1] = cal_u16(page, 0x04); cal.smin[1] = cal_u16(page, 0x06); cal.scenter[1] = cal_u16(page, 0x3A); /* right Y */
	cal.smax[2] = cal_u16(page, 0x08); cal.smin[2] = cal_u16(page, 0x0A); cal.scenter[2] = cal_u16(page, 0x34); /* left X  */
	cal.smax[3] = cal_u16(page, 0x0C); cal.smin[3] = cal_u16(page, 0x0E); cal.scenter[3] = cal_u16(page, 0x36); /* left Y  */
	cal.dead = cal_u16(page, 0x18);
	/* uint16 wraparound is intentional (offsets are stored as signed 16-bit). */
	cal.t_hi[0] = (uint16_t)(cal_u16(page, 0x10) + cal_u16(page, 0x1E));
	cal.t_lo[0] = (uint16_t)(cal_u16(page, 0x12) + cal_u16(page, 0x1C));
	cal.t_hi[1] = (uint16_t)(cal_u16(page, 0x14) + cal_u16(page, 0x22));
	cal.t_lo[1] = (uint16_t)(cal_u16(page, 0x16) + cal_u16(page, 0x20));
	cal.t_thresh = page[0x1A];
}

/* Signed -127..127, deadzone around center, full scale at 90% of travel. */
static int stick_scale(uint16_t raw, uint16_t max, uint16_t min, uint16_t center)
{
	int32_t out, span, v, th;

	if (raw > center) {
		th = (int32_t)center + cal.dead;
		v = raw < max ? raw : max;
		if (v < th) {
			v = th;
		}
		span = ((int32_t)max - th) * 9 / 10;
		out = span > 0 ? 127 * (v - th) / span : 0;
	} else {
		th = (int32_t)center - cal.dead;
		v = raw > min ? raw : min;
		if (v > th) {
			v = th;
		}
		span = (th - (int32_t)min) * 9 / 10;
		out = span > 0 ? -127 * (th - v) / span : 0;
	}
	if (out > 127) {
		out = 127;
	}
	if (out < -127) {
		out = -127;
	}
	return (int)out;
}

/* Trigger sensors read HIGH at rest and fall when pressed; return 0 (rest)..255. */
static uint8_t trigger_scale(uint16_t raw, uint16_t lo, uint16_t hi)
{
	uint32_t v, out;

	if (hi <= lo) {
		return 0;
	}
	v = raw < lo ? lo : raw > hi ? hi : raw;
	out = (v - lo) * 255 / (hi - lo);
	return (uint8_t)~out;
}

static void build_report(uint16_t buttons, const uint16_t adc[KISHI_ADC_COUNT])
{
	static uint8_t counter;
	uint8_t *r = input_report;
	unsigned i;
	int lx, ly, rx, ry;

	for (i = 0; i < sizeof(input_report); i++) {
		r[i] = 0;
	}
	r[0] = 0x01;

	rx = stick_scale(adc[0], cal.smax[0], cal.smin[0], cal.scenter[0]);
	ry = stick_scale(adc[1], cal.smax[1], cal.smin[1], cal.scenter[1]);
	lx = stick_scale(adc[4], cal.smax[2], cal.smin[2], cal.scenter[2]);
	ly = -stick_scale(adc[3], cal.smax[3], cal.smin[3], cal.scenter[3]);
	r[1] = (uint8_t)(lx + 128);
	r[2] = (uint8_t)(ly + 128);
	r[3] = (uint8_t)(rx + 128);
	r[4] = (uint8_t)(ry + 128);

	r[5] = hat_from_buttons(buttons);
	if (buttons & (1u << KB_X)) r[5] |= 0x10;   /* Square   */
	if (buttons & (1u << KB_A)) r[5] |= 0x20;   /* Cross    */
	if (buttons & (1u << KB_B)) r[5] |= 0x40;   /* Circle   */
	if (buttons & (1u << KB_Y)) r[5] |= 0x80;   /* Triangle */

	r[8] = trigger_scale(adc[5], cal.t_lo[0], cal.t_hi[0]);   /* L2 (PB0) */
	r[9] = trigger_scale(adc[2], cal.t_lo[1], cal.t_hi[1]);   /* R2 (PA3) */

	if (buttons & (1u << KB_L1)) r[6] |= 0x01;
	if (buttons & (1u << KB_R1)) r[6] |= 0x02;
	if (r[8] > cal.t_thresh) r[6] |= 0x04;      /* L2 digital */
	if (r[9] > cal.t_thresh) r[6] |= 0x08;      /* R2 digital */
	if (buttons & (1u << KB_LFUNC)) r[6] |= 0x10;   /* Share   */
	if (buttons & (1u << KB_RFUNC)) r[6] |= 0x20;   /* Options */
	if (buttons & (1u << KB_L3)) r[6] |= 0x40;
	if (buttons & (1u << KB_R3)) r[6] |= 0x80;

	r[7] = (buttons & (1u << KB_HOME)) ? 0x01 : 0x00;   /* PS */
	r[7] |= (uint8_t)(counter++ << 2);

#ifdef DIAG
	/* DIAG payload in the (unused) gyro/accel bytes 13..24 and 25..26. */
	for (i = 0; i < KISHI_ADC_COUNT; i++) {
		r[13 + 2 * i] = adc[i] & 0xFF;
		r[14 + 2 * i] = adc[i] >> 8;
	}
	r[25] = buttons & 0xFF;
	r[26] = buttons >> 8;
#endif

	r[30] = 0x1B;                       /* wired, battery full */
}

int main(void)
{
	uint16_t adc[KISHI_ADC_COUNT];
#ifdef DIAG
	unsigned beat = 0;
	unsigned loops = 0;

	snapshot_regs(snap_entry, 0);
	log_init();
	logw(0, 0xB007);
	logw(1, (uint16_t)(RCC_CSR >> 16));   /* latest reset cause; cleared below */
	RCC_CSR |= (1u << 24);                /* RMVF: next boot shows only its own cause */
#endif

	clock_setup();
#ifdef DIAG
	logw(2, (uint16_t)(RCC_CR >> 16));
	logw(3, (uint16_t)(REG32(0x40021034) >> 16));
#endif
	cal_load();
#ifdef DIAG
	logw(14, 0xCA11);
	kishi_trace = trace_cb;
#endif
	kishi_io_init();
#ifdef DIAG
	logw(4, 0xA001);
#endif
	delay_cycles(1500000);    /* let VBUS/VDD settle (~0.2 s) before attaching */
	usb_setup();
#ifdef DIAG
	logw(5, (uint16_t)USB_CNTR_REG);
	snapshot_regs(snap_final, 1);
#endif

	while (1) {
#ifdef DIAG
		if ((++beat & 0x3FF) == 0) {   /* ~3 Hz liveness blink on PB4 */
			gpio_toggle(GPIOB, GPIO4);
		}
		loops++;
		if (loops == 2) {
			logw(23, 0x1002);
		} else if (loops == 10) {
			logw(24, 0x1010);
			logw(27, (uint16_t)USB_ISTR_REG | 0x4000u);
		} else if (loops == 100) {
			logw(25, 0x1100);
		} else if (loops == 1000) {
			logw(26, 0x1999);
		}
		if (loops == 5000) {
			logw(7, 0x600D);                 /* main loop alive */
		} else if (loops == 30000) {         /* ~10 s: where did USB get to? */
			logw(10, (uint16_t)USB_ISTR_REG);
			logw(11, (uint16_t)USB_DADDR_REG);
			logw(12, (uint16_t)USB_CNTR_REG);
			logw(13, 0xD00D);
		}
#endif
		usbd_poll(usb_device);
#ifdef DIAG
		logw(15, 0x9011);                    /* first usbd_poll returned */
#endif
		kishi_read_adc(adc);
#ifdef DIAG
		logw(19, 0xAD0F);                    /* first full ADC pass done */
#endif
		{
			uint16_t btn = kishi_read_buttons();
#ifdef DIAG
			logw(20, 0xB070);                /* buttons read */
#endif
			build_report(btn, adc);
		}
#ifdef DIAG
		logw(21, 0xB011);                    /* report built */
#endif
		if (configured) {
			(void)usbd_ep_write_packet(usb_device, 0x81, input_report,
				sizeof(input_report));
		}
#ifdef DIAG
		logw(22, 0xE9E9);                    /* first ep write step passed */
#endif
	}
}
