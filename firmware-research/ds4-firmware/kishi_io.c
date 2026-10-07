#include "kishi_io.h"
#include "diag.h"

#include <libopencm3/stm32/rcc.h>
#include <libopencm3/stm32/gpio.h>
#include <libopencm3/stm32/adc.h>

static const struct {
	uint32_t port;
	uint16_t pin;
} button_pins[KB_COUNT] = {
	[KB_A]     = {GPIOC, GPIO2},
	[KB_B]     = {GPIOC, GPIO3},
	[KB_X]     = {GPIOC, GPIO0},
	[KB_Y]     = {GPIOC, GPIO1},
	[KB_UP]    = {GPIOB, GPIO2},
	[KB_DOWN]  = {GPIOB, GPIO1},
	[KB_LEFT]  = {GPIOB, GPIO11},
	[KB_RIGHT] = {GPIOB, GPIO10},
	[KB_L1]    = {GPIOC, GPIO5},
	[KB_R1]    = {GPIOA, GPIO0},
	[KB_L3]    = {GPIOC, GPIO4},
	[KB_R3]    = {GPIOC, GPIO13},
	[KB_RFUNC] = {GPIOA, GPIO8},
	[KB_HOME]  = {GPIOA, GPIO7},
	[KB_LFUNC] = {GPIOA, GPIO4},
};

static const uint8_t adc_channels[KISHI_ADC_COUNT] = {1, 2, 3, 5, 6, 8};

/* A conversion takes ~10 us; this bound (~20 ms at 48 MHz) only matters if the ADC is wedged. */
#define ADC_WAIT_LOOPS 100000u

void kishi_io_init(void)
{
	rcc_periph_clock_enable(RCC_GPIOA);
	rcc_periph_clock_enable(RCC_GPIOB);
	rcc_periph_clock_enable(RCC_GPIOC);
	rcc_periph_clock_enable(RCC_ADC);

	/* Buttons: inputs with pull-ups (stock: PC0-5,PC13 / PA0,4,7,8 / PB1,2,10,11). */
	gpio_mode_setup(GPIOC, GPIO_MODE_INPUT, GPIO_PUPD_PULLUP,
		GPIO0 | GPIO1 | GPIO2 | GPIO3 | GPIO4 | GPIO5 | GPIO13);
	gpio_mode_setup(GPIOA, GPIO_MODE_INPUT, GPIO_PUPD_PULLUP,
		GPIO0 | GPIO4 | GPIO7 | GPIO8);
	gpio_mode_setup(GPIOB, GPIO_MODE_INPUT, GPIO_PUPD_PULLUP,
		GPIO1 | GPIO2 | GPIO10 | GPIO11);

	/* Analog: PA1,2,3,5,6 and PB0. */
	gpio_mode_setup(GPIOA, GPIO_MODE_ANALOG, GPIO_PUPD_NONE,
		GPIO1 | GPIO2 | GPIO3 | GPIO5 | GPIO6);
	gpio_mode_setup(GPIOB, GPIO_MODE_ANALOG, GPIO_PUPD_NONE, GPIO0);

	/*
	 * Stock drives PB14 and PC9 high at boot (push-pull outputs; purpose not yet
	 * identified); match that.  PB4, the blue LED, is handled by led.c.
	 */
	gpio_set(GPIOB, GPIO14);
	gpio_set(GPIOC, GPIO9);
	gpio_mode_setup(GPIOB, GPIO_MODE_OUTPUT, GPIO_PUPD_NONE, GPIO14);
	gpio_set_output_options(GPIOB, GPIO_OTYPE_PP, GPIO_OSPEED_LOW, GPIO14);
	gpio_mode_setup(GPIOC, GPIO_MODE_OUTPUT, GPIO_PUPD_NONE, GPIO9);
	gpio_set_output_options(GPIOC, GPIO_OTYPE_PP, GPIO_OSPEED_LOW, GPIO9);

	/* ADC: 12-bit, PCLK/4 = 12 MHz (<= 14 MHz limit), long sample time for the pots. */
	adc_power_off(ADC1);
	adc_set_clk_source(ADC1, ADC_CLKSOURCE_PCLK_DIV4);
	adc_calibrate(ADC1);
	adc_set_single_conversion_mode(ADC1);
	adc_disable_external_trigger_regular(ADC1);
	adc_set_right_aligned(ADC1);
	adc_set_resolution(ADC1, ADC_CFGR1_RES_12_BIT);
	adc_set_sample_time_on_all_channels(ADC1, ADC_SMPTIME_071DOT5);
	adc_power_on(ADC1);
}

uint16_t kishi_read_buttons(void)
{
	uint16_t mask = 0;
	unsigned i;

	for (i = 0; i < KB_COUNT; i++) {
		if (!gpio_get(button_pins[i].port, button_pins[i].pin)) {
			mask |= (uint16_t)(1u << i);
		}
	}
	return mask;
}

void kishi_read_adc(uint16_t out[KISHI_ADC_COUNT])
{
	unsigned i, n;

	for (i = 0; i < KISHI_ADC_COUNT; i++) {
		uint8_t ch = adc_channels[i];
		uint32_t sum = 0;
		unsigned got = 0;

		adc_set_regular_sequence(ADC1, 1, &ch);
		for (n = 0; n < 4; n++) {
			unsigned t = ADC_WAIT_LOOPS;

			diag_mark(DM_ADC_CR, (uint16_t)ADC_CR(ADC1) | 0x8000u);
			adc_start_conversion_regular(ADC1);
			while (!adc_eoc(ADC1) && --t) {
			}
			if (!t) {
				diag_mark(DM_ADC_TIMEOUT, (uint16_t)ADC_ISR(ADC1) | 0x8000u);
				break;
			}
			sum += adc_read_regular(ADC1);
			got++;
			diag_mark(DM_FIRST_CONV, 0xAD0E);
		}
		out[i] = got ? (uint16_t)(sum / got) : 2048;
	}
}
