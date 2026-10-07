#include <libopencm3/stm32/gpio.h>
#include <libopencm3/stm32/rcc.h>
#include <libopencm3/stm32/timer.h>

#include "config.h"
#include "led.h"

/*
 * PB4 is TIM3_CH1 (AF1).  The LED is active-low: lit while the pin is low.  PWM1 with
 * inverted output polarity holds the pin low while CNT < CCR, so CCR/256 is the lit fraction
 * and CCR = 0 keeps the pin high (dark).
 */
#define LED_TIMER TIM3
#define PWM_PERIOD 255u

static uint16_t last_ccr = 0xFFFF;

void led_init(void)
{
	rcc_periph_clock_enable(RCC_TIM3);
	gpio_mode_setup(GPIOB, GPIO_MODE_AF, GPIO_PUPD_NONE, GPIO4);
	gpio_set_output_options(GPIOB, GPIO_OTYPE_PP, GPIO_OSPEED_LOW, GPIO4);
	gpio_set_af(GPIOB, GPIO_AF1, GPIO4);

	timer_set_mode(LED_TIMER, TIM_CR1_CKD_CK_INT, TIM_CR1_CMS_EDGE, TIM_CR1_DIR_UP);
	timer_set_prescaler(LED_TIMER, 187);          /* 48 MHz / 188 / 256 ~ 1 kHz */
	timer_set_period(LED_TIMER, PWM_PERIOD);
	timer_set_oc_mode(LED_TIMER, TIM_OC1, TIM_OCM_PWM1);
	timer_set_oc_polarity_low(LED_TIMER, TIM_OC1);
	timer_enable_oc_preload(LED_TIMER, TIM_OC1);
	timer_set_oc_value(LED_TIMER, TIM_OC1, 0);
	timer_enable_oc_output(LED_TIMER, TIM_OC1);
	timer_enable_preload(LED_TIMER);
	timer_enable_counter(LED_TIMER);
}

/* 0..255 -> perceptual duty (gamma 2), so mid brightness values look mid. */
static unsigned gamma_duty(unsigned level)
{
	return level * level / 255u;
}

static void set_duty(unsigned duty)   /* 0..255 */
{
	uint16_t ccr = duty >= 255u ? PWM_PERIOD + 1u : (uint16_t)duty;

	if (ccr != last_ccr) {
		last_ccr = ccr;
		timer_set_oc_value(LED_TIMER, TIM_OC1, ccr);
	}
}

#ifdef DIAG
static int forced_duty = -1;

void led_force(int duty)
{
	forced_duty = duty;
}
#endif

void led_update(uint32_t ms, int active)
{
	unsigned level = 0;

#ifdef DIAG
	if (forced_duty >= 0) {
		set_duty((unsigned)forced_duty);
		return;
	}
#endif

	switch (kcfg.led_mode) {
	case 1:   /* solid */
		level = kcfg.led_brightness;
		break;
	case 2: { /* breathing: triangle wave, period = led_breath * 100 ms */
		uint32_t period = (uint32_t)kcfg.led_breath * 100u;
		uint32_t phase = ms % period;
		uint32_t half = period / 2u;
		uint32_t tri = phase < half ? phase * 255u / half : (period - phase) * 255u / half;

		level = (unsigned)(tri * kcfg.led_brightness / 255u);
		break;
	}
	case 3:   /* solid only while the host has the device configured */
		level = active ? kcfg.led_brightness : 0;
		break;
	default:  /* off */
		level = 0;
		break;
	}
	set_duty(gamma_duty(level));
}
