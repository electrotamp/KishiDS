/*
 * Kishi V2 Pro RGB LED: three SCTimer PWM outputs, the way Razer's firmware 2.2 drives it (see led_v2pro.c).
 * Behaviour follows the V1's led.c and the shared config (led_mode, led_brightness, led_breath); the colour is the
 * DS4 lightbar colour once the host sets one (output report 0x05), the board default before that.
 */
#ifndef LED_V2PRO_H
#define LED_V2PRO_H

#include <stdint.h>

void led_init(void);

/* Call every main-loop pass: ms is a free-running millisecond count, active = USB configured. */
void led_update(uint32_t ms, int active);

/* DS4 lightbar colour from the host (output report 0x05). */
void led_set_lightbar(uint8_t r, uint8_t g, uint8_t b);

#endif
