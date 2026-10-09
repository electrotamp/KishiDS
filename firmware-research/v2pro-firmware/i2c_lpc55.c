/*
 * FLEXCOMM I2C master, polled (see i2c_lpc55.h).  Razer's 2.2: FLEXCOMM1..4 clocks attached to FRO 12 MHz
 * (CLOCK_AttachClk 0x315..0x318), reset through PRESETCTRL1 (0x1000c..0x1000f), I2C_MasterInit with 400 kHz
 * (0x61a80) from 12 MHz (0xb71b00).
 */
#include "i2c_lpc55.h"

#include "clock.h"
#include "lpc55.h"

static const uint32_t fc_base[8] = {
	0x40086000u, 0x40087000u, 0x40088000u, 0x40089000u, 0x4008A000u, 0x40096000u, 0x40097000u, 0x40098000u,
};

#define FC_REG(fc, off)   LPC55_REG32(fc_base[fc] + (off))
#define FC_PSELID(fc)     FC_REG(fc, 0xFF8)
#define I2C_CFG(fc)       FC_REG(fc, 0x800)
#define I2C_STAT(fc)      FC_REG(fc, 0x804)
#define I2C_CLKDIV(fc)    FC_REG(fc, 0x814)
#define I2C_MSTCTL(fc)    FC_REG(fc, 0x820)
#define I2C_MSTTIME(fc)   FC_REG(fc, 0x824)
#define I2C_MSTDAT(fc)    FC_REG(fc, 0x828)

#define PSELID_I2C        3u
#define CFG_MSTEN         (1u << 0)
#define STAT_MSTPENDING   (1u << 0)
#define STAT_MSTSTATE(s)  (((s) >> 1) & 7u)
#define STAT_ERRORS       ((1u << 4) | (1u << 6))   /* MSTARBLOSS, MSTSTSTPERR */
#define MST_IDLE          0u
#define MST_RX_READY      1u
#define MST_TX_READY      2u
#define MSTCTL_CONTINUE   (1u << 0)
#define MSTCTL_START      (1u << 1)
#define MSTCTL_STOP       (1u << 2)

#define FCCLKSEL(n)       LPC55_REG32(SYSCON_BASE + 0x2B0u + 4u * (n))
#define FCCLK_FRO12M      2u
#define AHBCLK1_FC(n)     (1u << (11u + (n)))

/* About 25 bit times at 400 kHz with the CPU at 48 MHz, far more than one byte takes. */
#define POLL_LIMIT        20000u

void i2c_init(unsigned fc)
{
	FCCLKSEL(fc) = FCCLK_FRO12M;
	SYSCON_AHBCLKCTRLSET(1) = AHBCLK1_FC(fc);
	clock_peripheral_reset(1, AHBCLK1_FC(fc));
	FC_PSELID(fc) = PSELID_I2C;
	I2C_CLKDIV(fc) = 2u;                  /* 12 MHz / 3 = 4 MHz */
	I2C_MSTTIME(fc) = 3u | (3u << 4);     /* SCL low 5, high 5 clocks: 4 MHz / 10 = 400 kHz */
	I2C_CFG(fc) = CFG_MSTEN;
}

/* Wait for the master to need attention; returns its state, or -1 on timeout or a bus error. */
static int wait_pending(unsigned fc)
{
	uint32_t n, st;

	for (n = 0; n < POLL_LIMIT; n++) {
		st = I2C_STAT(fc);
		if (st & STAT_ERRORS) {
			I2C_STAT(fc) = STAT_ERRORS;   /* write 1 to clear */
			return -1;
		}
		if (st & STAT_MSTPENDING) {
			return (int)STAT_MSTSTATE(st);
		}
	}
	return -1;
}

static int stop(unsigned fc, int result)
{
	I2C_MSTCTL(fc) = MSTCTL_STOP;
	(void)wait_pending(fc);
	return result;
}

/* START + address (write) + register byte.  0 if both were acknowledged. */
static int address_register(unsigned fc, uint8_t addr7, uint8_t reg)
{
	if (wait_pending(fc) != (int)MST_IDLE) {
		return -1;
	}
	I2C_MSTDAT(fc) = (uint32_t)addr7 << 1;
	I2C_MSTCTL(fc) = MSTCTL_START;
	if (wait_pending(fc) != (int)MST_TX_READY) {
		return stop(fc, -1);
	}
	I2C_MSTDAT(fc) = reg;
	I2C_MSTCTL(fc) = MSTCTL_CONTINUE;
	if (wait_pending(fc) != (int)MST_TX_READY) {
		return stop(fc, -1);
	}
	return 0;
}

int i2c_write_reg(unsigned fc, uint8_t addr7, uint8_t reg, uint8_t value)
{
	if (address_register(fc, addr7, reg)) {
		return -1;
	}
	I2C_MSTDAT(fc) = value;
	I2C_MSTCTL(fc) = MSTCTL_CONTINUE;
	return stop(fc, wait_pending(fc) == (int)MST_TX_READY ? 0 : -1);
}

int i2c_read_reg(unsigned fc, uint8_t addr7, uint8_t reg, uint8_t *value)
{
	if (address_register(fc, addr7, reg)) {
		return -1;
	}
	I2C_MSTDAT(fc) = ((uint32_t)addr7 << 1) | 1u;   /* repeated START, read */
	I2C_MSTCTL(fc) = MSTCTL_START;
	if (wait_pending(fc) != (int)MST_RX_READY) {
		return stop(fc, -1);
	}
	*value = (uint8_t)I2C_MSTDAT(fc);
	return stop(fc, 0);
}
