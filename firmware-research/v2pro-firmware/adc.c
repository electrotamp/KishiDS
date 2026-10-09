/*
 * LPADC0 driver.  The initialisation is the one Razer's firmware 2.2 performs on this board (traced by emulation:
 * ADC regulator on, clock = main clock / 8 = 12 MHz, CFG 0x10800040, calibration with 128 averages, one command per
 * analog pin, results in FIFO 0), with the calibration steps written after NXP's fsl_lpadc (BSD-3).
 *
 * Difference from Razer: Razer converts one channel per software trigger and waits in a loop for each result; here the
 * commands are chained (CMDn.NEXT = n + 1) so one trigger converts every channel, and nothing ever blocks.
 */
#include "adc.h"

#include "diag.h"
#include "lpc55.h"

#define ADC_CFG_RAZER 0x10800040u   /* PWREN, PUDLY 0x80, REFSEL 1 (as Razer) */
#define MAX_CHANNELS  15u

static unsigned n_channels;
static uint16_t seen;         /* bit per command received in the current scan */
static uint16_t pending[MAX_CHANNELS];

uint32_t adc_gain_correction(uint32_t gcc)
{
	/*
	 * fsl_lpadc: GCRa = 131072 / (131072 - GCC), then LPADC_GetGainConvResult() turns it into a 17-bit fixed-point value
	 * with 16 fraction bits, rounding down.  floor(2^16 * 2^17 / (2^17 - GCC)) is the same number without floats.
	 */
	return (uint32_t)((1ull << 33) / ((1ull << 17) - (gcc & ADC_GCC_GAIN_CAL_MASK)));
}

static void calibrate(void)
{
	ADC_CTRL |= ADC_CTRL_CALOFS;                        /* offset calibration */
	WAIT_UNTIL(ADC_STAT & ADC_STAT_CAL_RDY, DW_ADC_CALOFS);

	ADC_CTRL |= ADC_CTRL_CAL_REQ;                       /* gain calibration (both sides) */
	WAIT_UNTIL((ADC_GCC(0) & ADC_GCC_RDY) && (ADC_GCC(1) & ADC_GCC_RDY), DW_ADC_GCC);
	ADC_GCR(0) = adc_gain_correction(ADC_GCC(0));
	ADC_GCR(1) = adc_gain_correction(ADC_GCC(1));
	ADC_GCR(0) |= ADC_GCR_RDY;
	ADC_GCR(1) |= ADC_GCR_RDY;
	WAIT_UNTIL(ADC_STAT & ADC_STAT_CAL_RDY, DW_ADC_CAL_RDY);
}

void adc_init(const struct adc_channel *channels, unsigned count)
{
	unsigned i;

	n_channels = count > MAX_CHANNELS ? MAX_CHANNELS : count;

	/* Clock: main clock (96 MHz FRO) / 8 = 12 MHz, as Razer. */
	SYSCON_ADCCLKSEL = 0u;
	SYSCON_ADCCLKDIV = CLKDIV_RESET;
	SYSCON_ADCCLKDIV = 7u;
	WAIT_UNTIL(!(SYSCON_ADCCLKDIV & CLKDIV_REQFLAG), DW_ADCCLKDIV);
	SYSCON_AHBCLKCTRLSET(0) = AHBCLK0_ADC;
	SYSCON_PRESETCTRLCLR(0) = AHBCLK0_ADC;
	PMC_PDRUNCFGCLR0 = PDRUNCFG_LDOGPADC;

	ADC_CTRL = ADC_CTRL_RST;                            /* reset the configuration */
	ADC_CTRL = 0u;
	ADC_CTRL = ADC_CTRL_RSTFIFO0 | ADC_CTRL_RSTFIFO1;
	ADC_CTRL = ADC_CTRL_CAL_AVGS(7);                     /* 128 samples per calibration step; doze mode allowed */
	ADC_CFG = ADC_CFG_RAZER;
	ADC_PAUSE = 0u;
	ADC_FCTRL(0) = 0u;
	ADC_FCTRL(1) = 0u;
	ADC_CTRL |= ADC_CTRL_ADCEN;

	calibrate();

	/* One single-ended, 12-bit, unaveraged command per channel (Razer's settings), chained 1 -> 2 -> ... -> n. */
	for (i = 0; i < n_channels; i++) {
		ADC_CMDL(i + 1u) = (channels[i].channel & 0x1Fu) | (channels[i].side_b ? ADC_CMDL_CTYPE_B : 0u);
		ADC_CMDH(i + 1u) = (i + 1u < n_channels) ? ADC_CMDH_NEXT(i + 2u) : 0u;
	}
	ADC_TCTRL(0) = ADC_TCTRL_TCMD(1);                    /* trigger 0 starts command 1; results to FIFO 0 */
	seen = 0;
	ADC_SWTRIG = 1u;
}

int adc_poll(uint16_t *out)
{
	uint32_t r;
	unsigned cmd;
	unsigned i;

	if (!n_channels) {
		return 0;
	}
	while ((r = ADC_RESFIFO(0)) & ADC_RESFIFO_VALID) {
		cmd = ADC_RESFIFO_CMDSRC(r);
		if (cmd >= 1u && cmd <= n_channels) {
			pending[cmd - 1u] = (uint16_t)(ADC_RESFIFO_D(r) >> 3);   /* 12-bit result sits in bits 14:3 */
			seen |= (uint16_t)(1u << (cmd - 1u));
		}
	}
	if (seen != (uint16_t)((1u << n_channels) - 1u)) {
		return 0;
	}
	for (i = 0; i < n_channels; i++) {
		out[i] = pending[i];
	}
	seen = 0;
	ADC_SWTRIG = 1u;                                     /* next scan */
	return 1;
}
