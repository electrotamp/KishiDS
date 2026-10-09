/*
 * Stand-in for NXP's fsl_device_registers.h, providing only what TinyUSB's lpc_ip3511 driver uses: USB0's base
 * address, IRQ number and endpoint count, the CMSIS __IO qualifier and three NVIC helpers.  Values from NXP's
 * LPC55S69_cm33_core0.h / _features.h (BSD-3).  Keeps the whole MCUXpresso SDK out of this build.
 */
#ifndef FSL_DEVICE_REGISTERS_H
#define FSL_DEVICE_REGISTERS_H

#include <stdint.h>

#ifndef __IO
#define __IO volatile
#endif
#ifndef __I
#define __I volatile const
#endif
#ifndef __O
#define __O volatile
#endif

typedef enum {
	USB0_NEEDCLK_IRQn = 27,
	USB0_IRQn = 28,
} IRQn_Type;

#define USB0_BASE              0x40084000u
#define FSL_FEATURE_USB_EP_NUM 5

#define NVIC_ISER(n) (*(volatile uint32_t *)(0xE000E100u + 4u * (n)))
#define NVIC_ICER(n) (*(volatile uint32_t *)(0xE000E180u + 4u * (n)))
#define NVIC_ICPR(n) (*(volatile uint32_t *)(0xE000E280u + 4u * (n)))

static inline void NVIC_EnableIRQ(IRQn_Type irq)
{
	NVIC_ISER((uint32_t)irq >> 5) = 1u << ((uint32_t)irq & 31u);
}

static inline void NVIC_DisableIRQ(IRQn_Type irq)
{
	NVIC_ICER((uint32_t)irq >> 5) = 1u << ((uint32_t)irq & 31u);
	__asm volatile("dsb\n\tisb" ::: "memory");
}

static inline void NVIC_ClearPendingIRQ(IRQn_Type irq)
{
	NVIC_ICPR((uint32_t)irq >> 5) = 1u << ((uint32_t)irq & 31u);
}

#endif
