/*
 * LPADC0: up to 15 channels scanned as one chain of conversion commands started by a software trigger.
 * Non-blocking: adc_poll() collects whatever results are ready and starts the next scan when one completes.
 */
#ifndef ADC_H
#define ADC_H

#include <stdint.h>

struct adc_channel {
	uint8_t channel;   /* ADCH, 0..31 */
	uint8_t side_b;    /* 1 = B side (ADC0_8 and up on the LPC55) */
};

/* Power, clock (12 MHz), calibrate (offset and gain, as NXP's driver does) and program one command per channel. */
void adc_init(const struct adc_channel *channels, unsigned count);

/*
 * Read finished conversions into out[] (12-bit, in the order adc_init() was given) and restart the scan when it is
 * complete.  Returns 1 when a full new set was stored by this call.  Slots never converted yet keep their value.
 */
int adc_poll(uint16_t *out);

/* NXP's gain correction, GCR = 2^16 * 131072 / (131072 - GCC), in exact integer arithmetic (host-tested). */
uint32_t adc_gain_correction(uint32_t gcc);

#endif
