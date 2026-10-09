#ifndef CLOCK_H
#define CLOCK_H

#include <stdint.h>

/* CPU and bus at 48 MHz from the 96 MHz FRO (no core-voltage change). */
void clock_init(void);

/* Power, reset and clock USB0 (full speed, crystal-less) and put the port in device mode. */
void clock_usb0_init(void);

/*
 * Pulse a peripheral's reset (PRESETCTRLn bit) and wait until it is really asserted and released: touching a
 * peripheral still in reset faults.
 */
void clock_peripheral_reset(unsigned reg, uint32_t bit);

#define CPU_HZ 48000000u

#endif
