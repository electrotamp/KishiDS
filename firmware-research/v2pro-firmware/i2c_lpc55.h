/*
 * Minimal blocking I2C master on an LPC55 FLEXCOMM, for the Kishi V2 Pro's haptics drivers.  Set up as Razer's
 * firmware 2.2 sets up FLEXCOMM1 and FLEXCOMM4: function clock FRO 12 MHz, 400 kHz.  Every wait is bounded, so a
 * missing or stuck device costs a few hundred microseconds, never a hang.
 */
#ifndef I2C_LPC55_H
#define I2C_LPC55_H

#include <stdint.h>

/* FLEXCOMM n (0..7) as an I2C master at 400 kHz. */
void i2c_init(unsigned flexcomm);

/* One register write / read (8-bit register address).  0 on success, -1 on NACK, bus error or timeout. */
int i2c_write_reg(unsigned flexcomm, uint8_t addr7, uint8_t reg, uint8_t value);
int i2c_read_reg(unsigned flexcomm, uint8_t addr7, uint8_t reg, uint8_t *value);

#endif
