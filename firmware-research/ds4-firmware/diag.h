/*
 * Optional diagnostics (build with DIAG=1).  In release builds every call below
 * is an empty inline, so the firmware carries no logging, snapshots, heartbeat
 * or extra feature reports.
 */
#ifndef DIAG_H
#define DIAG_H

#include <stdint.h>

/* Boot-log milestone slots (half-word index inside a log record). */
enum diag_milestone {
	DM_ENTRY = 0, DM_RESET_CAUSE, DM_CLOCK_CR, DM_CLOCK_CR2, DM_IO_DONE, DM_USB_CNTR,
	DM_BUS_RESET, DM_LOOP_ALIVE, DM_CLASS_REQ, DM_SET_CONFIG,
	DM_ISTR_10S, DM_DADDR_10S, DM_CNTR_10S, DM_MARK_10S,
	DM_CAL_DONE, DM_FIRST_POLL, DM_ADC_CR, DM_ADC_TIMEOUT, DM_FIRST_CONV, DM_FIRST_ADC_PASS,
	DM_BUTTONS, DM_REPORT_BUILT, DM_EP_STEP, DM_LOOP2, DM_LOOP10, DM_LOOP100, DM_LOOP1000, DM_ISTR_LOOP10,
};

#ifdef DIAG
void diag_boot(void);
void diag_mark(unsigned idx, uint16_t value);
void diag_after_clock(void);
void diag_after_usb_setup(void);
void diag_loop(void);
void diag_fill_report(uint8_t *report, uint16_t buttons, const uint16_t *adc);
int diag_feature_report(uint8_t id, uint8_t **buf, uint16_t *len);
void diag_set_report(const uint8_t *buf, uint16_t len);
#else
static inline void diag_boot(void) {}
static inline void diag_mark(unsigned idx, uint16_t value) { (void)idx; (void)value; }
static inline void diag_after_clock(void) {}
static inline void diag_after_usb_setup(void) {}
static inline void diag_loop(void) {}
static inline void diag_fill_report(uint8_t *report, uint16_t buttons, const uint16_t *adc)
{
	(void)report; (void)buttons; (void)adc;
}
static inline void diag_set_report(const uint8_t *buf, uint16_t len) { (void)buf; (void)len; }
static inline int diag_feature_report(uint8_t id, uint8_t **buf, uint16_t *len)
{
	(void)id; (void)buf; (void)len;
	return 0;
}
#endif

#endif
