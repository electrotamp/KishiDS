/*
 * Host test of the USB layer: the real TinyUSB device stack (usbd + HID class) and the real usb_ds4.c, with the
 * hardware driver (dcd) replaced by a fake that records what would go on the bus.  A scripted host enumerates the
 * device and exercises every report path, and the bytes are compared with what the V1 firmware (verified on an
 * iPhone) sends.  Run with tests/run_host_tests.sh.
 */
#include <stdio.h>
#include <string.h>

#include "config.h"
#include "ds4_usb.h"
#include "live.h"
#include "device/dcd.h"
#include "tusb.h"
#include "usb_ds4.h"

static int failures, checks;
#define CHECK(cond, ...) do { checks++; if (!(cond)) { failures++; printf("FAIL %s:%d: ", __FILE__, __LINE__); \
	printf(__VA_ARGS__); printf("\n"); } } while (0)

/* ---------------- fake dcd: one pending transfer per endpoint ---------------- */

struct xfer {
	uint8_t *buf;
	uint16_t len;
	int pending;
};
static struct xfer ep_in[8], ep_out[8];
static uint16_t ep_size[2][8];
static int connected;
bool dcd_edpt_xfer(uint8_t rhport, uint8_t ep_addr, uint8_t *buffer, uint16_t total_bytes, bool is_isr);

/* Like the real lpc_ip3511 driver, which sets DEVICE_CONNECT in DEVCMDSTAT inside dcd_init. */
bool dcd_init(uint8_t rhport, const tusb_rhport_init_t *rh_init) { (void)rhport; (void)rh_init; connected = 1; return true; }
void dcd_int_handler(uint8_t rhport) { (void)rhport; }
void dcd_int_enable(uint8_t rhport) { (void)rhport; }
void dcd_int_disable(uint8_t rhport) { (void)rhport; }
void dcd_set_address(uint8_t rhport, uint8_t dev_addr) { (void)dev_addr; dcd_edpt_xfer(rhport, 0x80, NULL, 0, false); }
void dcd_remote_wakeup(uint8_t rhport) { (void)rhport; }
void dcd_connect(uint8_t rhport) { (void)rhport; connected = 1; }
void dcd_disconnect(uint8_t rhport) { (void)rhport; connected = 0; }
void dcd_sof_enable(uint8_t rhport, bool en) { (void)rhport; (void)en; }
void dcd_edpt0_status_complete(uint8_t rhport, tusb_control_request_t const *request) { (void)rhport; (void)request; }
void dcd_edpt_close_all(uint8_t rhport) { (void)rhport; memset(ep_size, 0, sizeof(ep_size)); }
void dcd_edpt_stall(uint8_t rhport, uint8_t ep_addr) { (void)rhport; (void)ep_addr; }
void dcd_edpt_clear_stall(uint8_t rhport, uint8_t ep_addr) { (void)rhport; (void)ep_addr; }
void dcd_edpt_close(uint8_t rhport, uint8_t ep_addr) { (void)rhport; ep_size[ep_addr >> 7][ep_addr & 7] = 0; }
uint32_t tusb_time_millis_api(void) { return 0; }

bool dcd_edpt_open(uint8_t rhport, tusb_desc_endpoint_t const *d)
{
	(void)rhport;
	ep_size[d->bEndpointAddress >> 7][d->bEndpointAddress & 7] = tu_edpt_packet_size(d);
	return true;
}

bool dcd_edpt_xfer(uint8_t rhport, uint8_t ep_addr, uint8_t *buffer, uint16_t total_bytes, bool is_isr)
{
	struct xfer *x = (ep_addr & 0x80) ? &ep_in[ep_addr & 7] : &ep_out[ep_addr & 7];

	(void)rhport;
	(void)is_isr;
	x->buf = buffer;
	x->len = total_bytes;
	x->pending = 1;
	return true;
}

static void pump(void)
{
	for (int i = 0; i < 50; i++) {
		usb_ds4_task();
	}
}

/* Complete whatever IN transfer EP0 has queued, appending its bytes to out. */
static int take_in0(uint8_t *out, int at)
{
	struct xfer *x = &ep_in[0];
	uint16_t n;

	if (!x->pending) {
		return -1;
	}
	n = x->len;
	if (n && out) {
		memcpy(out + at, x->buf, n);
	}
	x->pending = 0;
	dcd_event_xfer_complete(0, 0x80, n, XFER_RESULT_SUCCESS, false);
	pump();
	return n;
}

/*
 * One control transfer.  Device-to-host: collects the data stage into in (returns its length), then completes the
 * host's zero-length status OUT.  Host-to-device: feeds out_data into the OUT stage, then completes the status IN.
 */
static int control(uint8_t bm, uint8_t req, uint16_t value, uint16_t index, uint16_t length,
		   const uint8_t *out_data, uint8_t *in)
{
	const uint8_t setup[8] = {bm, req, value & 0xFF, value >> 8, index & 0xFF, index >> 8, length & 0xFF, length >> 8};
	int got = 0, n;

	dcd_event_setup_received(0, setup, false);
	pump();
	if (bm & 0x80) {
		while (ep_in[0].pending && (n = take_in0(in, got)) > 0) {
			got += n;
		}
		if (ep_in[0].pending) {   /* zero-length packet ending a short transfer */
			take_in0(NULL, 0);
		}
		if (ep_out[0].pending) {
			ep_out[0].pending = 0;
			dcd_event_xfer_complete(0, 0x00, 0, XFER_RESULT_SUCCESS, false);
			pump();
		}
		return got;
	}
	if (length && ep_out[0].pending) {
		memcpy(ep_out[0].buf, out_data, length);
		ep_out[0].pending = 0;
		dcd_event_xfer_complete(0, 0x00, length, XFER_RESULT_SUCCESS, false);
		pump();
	}
	take_in0(NULL, 0);
	return 0;
}

/* ---------------- stand-ins for the hardware the USB layer touches ---------------- */

void clock_usb0_init(void) {}
void hw_apply_config(void) {}
int hw_flash_save(const struct kcfg_saved *r) { (void)r; return -1; }
int hw_flash_erase(void) { return -1; }
void hw_reboot(void) {}
static const uint8_t test_uid[16] = {0x5A, 0x74, 0x36, 0x7F, 0xFC, 0x1B, 0x4E, 0x59, 0xA8, 0x49, 0x42, 0x04,
				     0x54, 0x9F, 0x29, 0x23};
int board_uid(uint8_t out[16]) { memcpy(out, test_uid, 16); return 0; }
void led_set_lightbar(uint8_t r, uint8_t g, uint8_t b) { (void)r; (void)g; (void)b; }
uint8_t haptics_status[2];
#include "usblog_v2pro.h"
struct usblog usblog;
void usblog_get_report(uint8_t id) { (void)id; }
void usblog_set_report(uint8_t id) { (void)id; }

static uint8_t blank_saved_page[512];

/* ---------------- the test ---------------- */

static const uint8_t expected_device[18] = {
	0x12, 0x01, 0x00, 0x02, 0x00, 0x00, 0x00, 0x40, 0x4C, 0x05, 0xC4, 0x05, 0x00, 0x01, 0x01, 0x02, 0x03, 0x01,
};

int main(void)
{
	uint8_t buf[1024];
	uint8_t report[64] = {0x01, 0x80, 0x80, 0x80, 0x80, 0x08};
	int n;

	memset(blank_saved_page, 0xFF, sizeof(blank_saved_page));
	kcfg_test_saved = blank_saved_page;
	kcfg_load();
	live_init();
	usb_ds4_init();
	pump();
	CHECK(connected, "device did not connect");

	CHECK(usb_ds4_send(report) == -1 && !ep_in[1].pending, "IN endpoint written before SET_CONFIGURATION");

	dcd_event_bus_reset(0, TUSB_SPEED_FULL, false);
	pump();

	n = control(0x80, 6, 0x0100, 0, 64, NULL, buf);
	CHECK(n == 18 && !memcmp(buf, expected_device, 18), "device descriptor (%d bytes)", n);

	control(0x00, 5, 7, 0, 0, NULL, NULL);   /* SET_ADDRESS 7 */

	n = control(0x80, 6, 0x0200, 0, 255, NULL, buf);
	{
		const uint8_t expected_config[41] = {
			9, 2, 41, 0, 1, 1, 0, 0x80, 50,
			9, 4, 0, 0, 2, 3, 0, 0, 0,
			9, 0x21, 0x11, 0x01, 0, 1, 0x22, ds4_report_descriptor_len & 0xFF, ds4_report_descriptor_len >> 8,
			7, 5, 0x81, 3, 64, 0, 5,
			7, 5, 0x01, 3, 32, 0, 5,
		};
		CHECK(n == 41 && !memcmp(buf, expected_config, 41), "configuration descriptor (%d bytes)", n);
	}

	n = control(0x80, 6, 0x0300, 0, 255, NULL, buf);
	CHECK(n == 4 && buf[0] == 4 && buf[1] == 3 && buf[2] == 0x09 && buf[3] == 0x04, "string 0 (languages)");
	n = control(0x80, 6, 0x0302, 0x0409, 255, NULL, buf);
	{
		int ok = n == 2 + 2 * (int)strlen(kcfg.product) && buf[1] == 3;

		for (int i = 0; ok && kcfg.product[i]; i++) {
			ok = buf[2 + 2 * i] == (uint8_t)kcfg.product[i] && buf[3 + 2 * i] == 0;
		}
		CHECK(ok, "string 2 is the configured product name \"%s\"", kcfg.product);
	}
	n = control(0x80, 6, 0x0303, 0x0409, 255, NULL, buf);
	CHECK(n > 2, "serial number string present");
	{   /* "5A74367FFC1B4E59A8494204" in UTF-16 */
		static const char want[] = "5A74367FFC1B4E59A8494204";
		int ok = n == 2 + 2 * 24;

		for (unsigned i = 0; ok && i < 24; i++) {
			ok = buf[2 + 2 * i] == (uint8_t)want[i] && buf[3 + 2 * i] == 0;
		}
		CHECK(ok, "serial number is the chip UUID's first 96 bits (%s)", want);
	}

	control(0x00, 9, 1, 0, 0, NULL, NULL);   /* SET_CONFIGURATION 1 */
	CHECK(usb_ds4_configured(), "not configured after SET_CONFIGURATION");
	CHECK(ep_size[1][1] == 64 && ep_size[0][1] == 32, "endpoints 0x81/64 and 0x01/32 opened (%u, %u)", ep_size[1][1],
	      ep_size[0][1]);

	n = control(0x81, 6, 0x2200, 0, 1024, NULL, buf);
	CHECK(n == ds4_report_descriptor_len && !memcmp(buf, ds4_report_descriptor, n), "HID report descriptor (%d bytes)", n);

	/* Fixed feature reports: same bytes as the shared table (and so as the V1). */
	{
		const uint8_t ids[] = {0x02, 0xA3, 0x12, 0x81, 0x04, 0xF0, 0xB2};
		uint8_t want_addr[6];

		for (unsigned i = 0; i < 6; i++) {
			want_addr[i] = (uint8_t)(test_uid[i] ^ test_uid[i + 6] ^ (i < 4 ? test_uid[i + 12] : 0));
		}
		want_addr[5] = (uint8_t)((want_addr[5] & 0xFC) | 0x02);
		n = control(0xA1, 1, 0x0312, 0, 16, NULL, buf);
		CHECK(n == 16 && !memcmp(buf + 1, want_addr, 6), "feature 0x12 carries the address folded from the UUID");
		n = control(0xA1, 1, 0x0381, 0, 7, NULL, buf);
		CHECK(n == 7 && !memcmp(buf + 1, want_addr, 6), "feature 0x81 carries the same address");

		for (unsigned i = 0; i < sizeof(ids); i++) {
			uint8_t scratch[64];
			const uint8_t *want;
			uint16_t len = ds4_fixed_feature(ids[i], scratch, &want);

			n = control(0xA1, 1, (uint16_t)(0x0300 | ids[i]), 0, len, NULL, buf);
			CHECK(n == len && !memcmp(buf, want, len), "GET_REPORT feature 0x%02X (%d of %u bytes)", ids[i], n, len);
		}
	}

	n = control(0xA1, 1, 0x03AB, 0, 58, NULL, buf);
	CHECK(n == 58 && buf[0] == 0xAB && buf[1] == 1 && (buf[2] & 1) && buf[25] == LIVE_PROTOCOL,
	      "telemetry 0xAB (%d bytes, flags %#x)", n, buf[2]);

	/* Live editing round trip: SET_REPORT READSEL chunk 2, then GET_REPORT shows it selected. */
	{
		uint8_t set[LIVE_REPORT_LEN] = {LIVE_REPORT_ID, LIVE_SIGNATURE, LC_READSEL, 2};

		control(0x21, 9, 0x03AC, 0, sizeof(set), set, NULL);
		n = control(0xA1, 1, 0x03AC, 0, LIVE_REPORT_LEN, NULL, buf);
		CHECK(n == LIVE_REPORT_LEN && buf[0] == LIVE_REPORT_ID && buf[3] == LC_READSEL && buf[2] == LS_OK && buf[13] == 2,
		      "live 0xAC SET/GET round trip (status %u, cmd %u, chunk %u)", buf[2], buf[3], buf[13]);
	}

	CHECK(usb_ds4_send(report) == 0 && ep_in[1].pending && ep_in[1].len == 64 && ep_in[1].buf[0] == 0x01 &&
	      !memcmp(ep_in[1].buf, report, 64), "input report goes out on 0x81 as 64 bytes with ID 0x01");
	CHECK(usb_ds4_send(report) == -1, "second report refused while the first is in flight");
	ep_in[1].pending = 0;
	dcd_event_xfer_complete(0, 0x81, 64, XFER_RESULT_SUCCESS, false);
	pump();
	CHECK(usb_ds4_send(report) == 0, "next report accepted after completion");

	n = control(0xA1, 1, 0x0301, 0, 64, NULL, buf);
	CHECK(n == 64 && buf[0] == 0x01 && !memcmp(buf, report, 64), "GET_REPORT 0x01 returns the last input report");

	printf("%d checks, %d failures\n", checks, failures);
	return failures ? 1 : 0;
}
