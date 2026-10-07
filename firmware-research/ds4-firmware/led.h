/* Blue LED on PB4 (TIM3_CH1 PWM, active-low), driven according to the config block. */
#ifndef LED_H
#define LED_H

#include <stdint.h>

/* Set up TIM3 PWM on PB4; call after the clocks are running. */
void led_init(void);

/* Call every loop iteration: ms is a free-running millisecond counter, active != 0 once the host has configured the device. */
void led_update(uint32_t ms, int active);

#ifdef DIAG
/* Diagnostic builds: force the LED to a fixed duty (0..255) for the heartbeat; -1 returns control. */
void led_force(int duty);
#endif

#endif
