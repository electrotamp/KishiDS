/*
 * Kishi 0290 board I/O: button GPIOs, stick/trigger ADC channels.
 *
 * Pin map recovered from stock v2.70 (scanner at image offset 0x544C,
 * MX_GPIO_Init at 0x5A8C).  All buttons are active-low with pull-ups.
 */

#ifndef KISHI_IO_H
#define KISHI_IO_H

#include <stdint.h>

/* Bit index == stock scan-array index; a set bit means "pressed". */
enum kishi_button {
	KB_A = 0,       /* PC2  */
	KB_B,           /* PC3  */
	KB_X,           /* PC0  */
	KB_Y,           /* PC1  */
	KB_UP,          /* PB2  */
	KB_DOWN,        /* PB1  */
	KB_LEFT,        /* PB11 */
	KB_RIGHT,       /* PB10 */
	KB_L1,          /* PC5  */
	KB_R1,          /* PA0  */
	KB_L3,          /* PC4  */
	KB_R3,          /* PC13 */
	KB_RFUNC,       /* PA8  (right Function) */
	KB_HOME,        /* PA7  */
	KB_LFUNC,       /* PA4  (left Function) */
	KB_COUNT
};

/*
 * ADC channels, in stock raw[] order (ascending channel number):
 *   raw[0] ADC1  PA1  right stick X
 *   raw[1] ADC2  PA2  right stick Y
 *   raw[2] ADC3  PA3  R2 trigger
 *   raw[3] ADC5  PA5  left stick Y
 *   raw[4] ADC6  PA6  left stick X
 *   raw[5] ADC8  PB0  L2 trigger
 */
#define KISHI_ADC_COUNT 6

void kishi_io_init(void);
uint16_t kishi_read_buttons(void);
void kishi_read_adc(uint16_t out[KISHI_ADC_COUNT]);

#endif
