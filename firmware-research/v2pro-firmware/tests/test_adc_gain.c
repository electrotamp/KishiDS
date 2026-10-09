/*
 * adc_gain_correction() (integer) against NXP's LPADC_GetGainConvResult() float algorithm (fsl_lpadc.c, BSD-3,
 * reproduced below as the reference) for every possible GCC value.
 */
#include <stdio.h>
#include <stdint.h>

#include "adc.h"

static uint32_t nxp_gain(uint32_t gcc)
{
	float gain = (float)((131072.0) / (131072.0 - (double)gcc));
	uint32_t gcra[17] = {0}, gcalr = 0, tmp32;
	uint16_t i;

	for (i = 0x11U; i > 0U; i--) {
		tmp32 = (uint32_t)((gain) / ((float)(1.0 / (double)(1U << (0x10U - (i - 1U))))));
		gcra[i - 1U] = tmp32;
		gain = gain - ((float)tmp32) * ((float)(1.0 / (double)(1U << (0x10U - (i - 1U)))));
	}
	for (i = 0x11U; i > 0U; i--) {
		gcalr += gcra[i - 1U] * ((uint32_t)(1UL << (uint32_t)(i - 1UL)));
	}
	return gcalr;
}

int main(void)
{
	unsigned same = 0, off_by_one = 0, worse = 0;
	uint32_t gcc;

	for (gcc = 0; gcc <= 0xFFFF; gcc++) {
		uint32_t a = adc_gain_correction(gcc), b = nxp_gain(gcc);
		uint32_t d = a > b ? a - b : b - a;

		if (d == 0) same++;
		else if (d == 1) off_by_one++;
		else { worse++; if (worse < 5) printf("gcc %#x: ours %#x, NXP %#x\n", gcc, a, b); }
	}
	printf("%u identical, %u differ by 1 LSB (float rounding in NXP's version), %u differ more\n", same, off_by_one, worse);
	return worse ? 1 : 0;
}
