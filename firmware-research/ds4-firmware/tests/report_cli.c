/*
 * Host reference for the differential test (tests/emu_test.py): reads lines of
 *   <512 hex chars config> <hex buttons> <adc0..adc5 decimal> <counter>
 * and prints the 64-byte report as hex.  Uses the same report.c that is built into the firmware.
 */
#include <stdio.h>
#include <string.h>

#include "../report.h"

int main(void)
{
	char line[2048];

	while (fgets(line, sizeof(line), stdin)) {
		struct kishi_config cfg;
		struct kishi_cal cal;
		uint16_t adc[KISHI_ADC_COUNT];
		uint8_t out[KISHI_REPORT_SIZE];
		unsigned buttons, counter, a[KISHI_ADC_COUNT], i;
		char *p = line;
		uint8_t *cb = (uint8_t *)&cfg;

		for (i = 0; i < sizeof(cfg); i++) {
			unsigned v;

			if (sscanf(p, "%2x", &v) != 1) {
				return 2;
			}
			cb[i] = (uint8_t)v;
			p += 2;
		}
		if (sscanf(p, " %x %u %u %u %u %u %u %u", &buttons, &a[0], &a[1], &a[2], &a[3], &a[4], &a[5], &counter) != 8) {
			return 3;
		}
		for (i = 0; i < KISHI_ADC_COUNT; i++) {
			adc[i] = (uint16_t)a[i];
		}
		kcfg_clamp(&cfg);
		report_cal_from_config(&cal, &cfg);
		report_build(&cfg, &cal, (uint16_t)buttons, adc, (uint8_t)counter, out);
		for (i = 0; i < KISHI_REPORT_SIZE; i++) {
			printf("%02x", out[i]);
		}
		printf("\n");
	}
	return 0;
}
