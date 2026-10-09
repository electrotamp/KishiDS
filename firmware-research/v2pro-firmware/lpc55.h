/*
 * The few LPC55xx registers this firmware touches, as plain addresses (no vendor SDK yet).
 *
 * Offsets are from the LPC55S6x user manual and cross-checked against Razer's own 2.2 firmware where it uses them
 * (see ../BOARD_MAP_V2PRO.md): BOARD_InitPins at 0x153ce writes bits 13/14/15 to AHBCLKCTRLSET0, reads buttons
 * through the GPIO byte registers and configures pins through IOCON PIO[port][pin].
 */
#ifndef LPC55_H
#define LPC55_H

#include <stdint.h>

#define LPC55_REG32(a) (*(volatile uint32_t *)(a))
#define LPC55_REG8(a)  (*(volatile uint8_t *)(a))

/*
 * SYSCON.  Offsets and bits from NXP's LPC55S69_cm33_core0.h / fsl_clock / fsl_reset (BSD-3); every one used here also
 * appears in Razer's start-up sequence (logged by emulating firmware 2.2).
 */
#define SYSCON_BASE            0x40000000u
#define SYSCON_PRESETCTRL(n)   LPC55_REG32(SYSCON_BASE + 0x100u + 4u * (n))
#define SYSCON_PRESETCTRLSET(n) LPC55_REG32(SYSCON_BASE + 0x120u + 4u * (n))
#define SYSCON_PRESETCTRLCLR(n) LPC55_REG32(SYSCON_BASE + 0x140u + 4u * (n))
#define SYSCON_AHBCLKCTRLSET(n) LPC55_REG32(SYSCON_BASE + 0x220u + 4u * (n))
#define SYSCON_AHBCLKCTRLCLR(n) LPC55_REG32(SYSCON_BASE + 0x240u + 4u * (n))
#define SYSCON_AHBCLKCTRLSET0  SYSCON_AHBCLKCTRLSET(0)
#define SYSCON_MAINCLKSELA     LPC55_REG32(SYSCON_BASE + 0x280u)   /* 3 = FRO_HF (96 MHz) */
#define SYSCON_MAINCLKSELB     LPC55_REG32(SYSCON_BASE + 0x284u)   /* 0 = main_clk_a */
#define SYSCON_ADCCLKSEL       LPC55_REG32(SYSCON_BASE + 0x2A4u)   /* 0 = main clock */
#define SYSCON_USB0CLKSEL      LPC55_REG32(SYSCON_BASE + 0x2A8u)   /* 3 = FRO_HF */
#define SYSCON_AHBCLKDIV       LPC55_REG32(SYSCON_BASE + 0x380u)   /* divide by DIV + 1 */
#define SYSCON_ADCCLKDIV       LPC55_REG32(SYSCON_BASE + 0x394u)   /* divide by DIV + 1 */
#define SYSCON_USB0CLKDIV      LPC55_REG32(SYSCON_BASE + 0x398u)   /* divide by DIV + 1 */
#define SYSCON_SCTCLKSEL       LPC55_REG32(SYSCON_BASE + 0x2F0u)   /* 0 = main clock; resets to 7 (none) */
#define SYSCON_SCTCLKDIV       LPC55_REG32(SYSCON_BASE + 0x3B4u)   /* divide by DIV + 1 */
#define SYSCON_FMCCR           LPC55_REG32(SYSCON_BASE + 0x400u)
#define CLKDIV_REQFLAG         (1u << 31)
#define CLKDIV_RESET           (1u << 29)
#define FMCCR_FLASHTIM_MASK    0xF000u
#define FMCCR_PREFEN           0x20u
/* AHBCLKCTRL0 */
#define AHBCLK0_SRAM1          (1u << 3)    /* SRAM1-4: Razer's SystemInit sets all four (0x78) */
#define AHBCLK0_SRAM2          (1u << 4)
#define AHBCLK0_SRAM3          (1u << 5)
#define AHBCLK0_SRAM4          (1u << 6)
#define AHBCLK0_IOCON          (1u << 13)
#define AHBCLK0_GPIO0          (1u << 14)
#define AHBCLK0_GPIO1          (1u << 15)
#define AHBCLK0_ADC            (1u << 27)   /* also the ADC0 bit in PRESETCTRL0 */
/* AHBCLKCTRL1 / PRESETCTRL1 */
#define AHBCLK1_USB0D          (1u << 25)
/* AHBCLKCTRL2 / PRESETCTRL2 */
#define AHBCLK2_USB1RAM        (1u << 6)
#define AHBCLK2_USB0HMR        (1u << 16)
#define AHBCLK2_USB0HSL        (1u << 17)
#define AHBCLK2_ANALOG_CTRL    (1u << 27)

/* PMC: analog power (write-1-to-clear powers the block up). */
#define PMC_BASE               0x40020000u
#define PMC_PDRUNCFGCLR0       LPC55_REG32(PMC_BASE + 0xC8u)
#define PDRUNCFG_FRO192M       (1u << 5)
#define PDRUNCFG_USB0_PHY      (1u << 11)
#define PDRUNCFG_LDOGPADC      (1u << 19)   /* the ADC's regulator */

/* ANACTRL: the 192 MHz FRO and its outputs. */
#define ANACTRL_BASE           0x40013000u
#define ANACTRL_FRO192M_CTRL   LPC55_REG32(ANACTRL_BASE + 0x10u)
#define FRO192M_USBCLKADJ      (1u << 24)   /* trim the FRO from USB SOF packets (crystal-less USB) */
#define FRO192M_ENA_96MHZCLK   (1u << 30)

/*
 * Flash controller.  Commands and sequences as in the register-level flash driver Razer's firmware uses on this board
 * (erase 0xa5de, program 0xa6dc, read 0xa85e, wait 0x151fc in firmware 2.2).  Addresses go in STARTA/STOPA as
 * address >> 4 (16-byte flash words); pages are 512 bytes.
 */
#define FLASH_BASE             0x40034000u
#define FLASH_CMD              LPC55_REG32(FLASH_BASE + 0x000u)
#define FLASH_STARTA           LPC55_REG32(FLASH_BASE + 0x010u)
#define FLASH_STOPA            LPC55_REG32(FLASH_BASE + 0x014u)
#define FLASH_DATAW(n)         LPC55_REG32(FLASH_BASE + 0x080u + 4u * (n))
#define FLASH_DATAW0           FLASH_DATAW(0)
#define FLASH_INT_STATUS       LPC55_REG32(FLASH_BASE + 0xFE0u)
#define FLASH_INT_CLR_STATUS   LPC55_REG32(FLASH_BASE + 0xFE8u)
#define FLASH_CMD_SET_READ_MODE 2u
#define FLASH_CMD_READ_SINGLE_WORD 3u   /* 16 bytes into DATAW[0..3]; an unreadable word reports an error, not a fault */
#define FLASH_CMD_ERASE_RANGE  4u
#define FLASH_CMD_WRITE        8u       /* DATAW[0..3] into the page buffer at STARTA */
#define FLASH_CMD_PROGRAM      12u      /* page buffer into the page */
#define FLASH_INT_FAIL         (1u << 0)
#define FLASH_INT_ERR          (1u << 1)
#define FLASH_INT_DONE         (1u << 2)
#define FLASH_INT_ECC_ERR      (1u << 3)
#define FLASH_INT_ERRORS       (FLASH_INT_FAIL | FLASH_INT_ERR | FLASH_INT_ECC_ERR | (1u << 4))
#define FLASH_PAGE_SIZE        512u
#define SYSCON_FMCFLUSH        LPC55_REG32(SYSCON_BASE + 0x41Cu)   /* 1 = drop the flash read buffers */

/* USB0 full-speed: device controller and the host block that owns the port-mode switch. */
#define USB0_BASE_ADDR         0x40084000u
#define USBFSH_BASE            0x400A2000u
#define USBFSH_PORTMODE        LPC55_REG32(USBFSH_BASE + 0x5Cu)
#define PORTMODE_DEV_ENABLE    (1u << 16)

/* LPADC0 (NXP LPC55S69_cm33_core0.h / fsl_lpadc). */
#define ADC_BASE               0x400A0000u
#define ADC_CTRL               LPC55_REG32(ADC_BASE + 0x010u)
#define ADC_STAT               LPC55_REG32(ADC_BASE + 0x014u)
#define ADC_CFG                LPC55_REG32(ADC_BASE + 0x020u)
#define ADC_PAUSE              LPC55_REG32(ADC_BASE + 0x024u)
#define ADC_SWTRIG             LPC55_REG32(ADC_BASE + 0x034u)
#define ADC_TCTRL(n)           LPC55_REG32(ADC_BASE + 0x0A0u + 4u * (n))
#define ADC_FCTRL(n)           LPC55_REG32(ADC_BASE + 0x0E0u + 4u * (n))
#define ADC_GCC(n)             LPC55_REG32(ADC_BASE + 0x0F0u + 4u * (n))
#define ADC_GCR(n)             LPC55_REG32(ADC_BASE + 0x0F8u + 4u * (n))
#define ADC_CMDL(n)            LPC55_REG32(ADC_BASE + 0x100u + 8u * ((n) - 1u))   /* commands are numbered from 1 */
#define ADC_CMDH(n)            LPC55_REG32(ADC_BASE + 0x104u + 8u * ((n) - 1u))
#define ADC_RESFIFO(n)         LPC55_REG32(ADC_BASE + 0x300u + 4u * (n))
#define ADC_CTRL_ADCEN         (1u << 0)
#define ADC_CTRL_RST           (1u << 1)
#define ADC_CTRL_CAL_REQ       (1u << 3)
#define ADC_CTRL_CALOFS        (1u << 4)
#define ADC_CTRL_RSTFIFO0      (1u << 8)
#define ADC_CTRL_RSTFIFO1      (1u << 9)
#define ADC_CTRL_CAL_AVGS(n)   ((uint32_t)(n) << 16)
#define ADC_STAT_CAL_RDY       (1u << 10)
#define ADC_GCC_GAIN_CAL_MASK  0xFFFFu
#define ADC_GCC_RDY            (1u << 24)
#define ADC_GCR_RDY            (1u << 24)
#define ADC_CMDL_CTYPE_B       (1u << 5)    /* single-ended, B side */
#define ADC_CMDH_NEXT(n)       ((uint32_t)(n) << 24)
#define ADC_TCTRL_TCMD(n)      ((uint32_t)(n) << 24)
#define ADC_FCTRL_FCOUNT_MASK  0x1Fu
#define ADC_RESFIFO_VALID      (1u << 31)
#define ADC_RESFIFO_CMDSRC(r)  (((r) >> 24) & 0xFu)
#define ADC_RESFIFO_D(r)       ((r) & 0xFFFFu)

/* IOCON: one 32-bit register per pin. */
#define IOCON_BASE             0x40001000u
#define IOCON_PIO(port, pin)   LPC55_REG32(IOCON_BASE + 0x80u * (port) + 4u * (pin))
#define IOCON_FUNC0            0x000u
#define IOCON_MODE_PULLUP      0x020u
#define IOCON_DIGIMODE         0x100u
#define IOCON_ASW              0x400u   /* analog switch: pin routed to the ADC (DIGIMODE must be 0) */

/* GPIO: byte register per pin (reads 0 or 1), per-port direction / set / clear. */
#define GPIO_BASE              0x4008C000u
#define GPIO_B(port, pin)      LPC55_REG8(GPIO_BASE + 0x20u * (port) + (pin))
#define GPIO_DIR(port)         LPC55_REG32(GPIO_BASE + 0x2000u + 4u * (port))
#define GPIO_SET(port)         LPC55_REG32(GPIO_BASE + 0x2200u + 4u * (port))
#define GPIO_CLR(port)         LPC55_REG32(GPIO_BASE + 0x2280u + 4u * (port))

/* Cortex-M33 system control. */
#define SCB_VTOR               LPC55_REG32(0xE000ED08u)   /* Razer's SystemInit sets it to 0x8000 (at 0xed26) */
#define SYST_CSR               LPC55_REG32(0xE000E010u)
#define SYST_RVR               LPC55_REG32(0xE000E014u)
#define SYST_CVR               LPC55_REG32(0xE000E018u)
#define SCB_NVIC_ICER(n)       LPC55_REG32(0xE000E180u + 4u * (n))
#define SCB_NVIC_ICPR(n)       LPC55_REG32(0xE000E280u + 4u * (n))
#define SCB_AIRCR              LPC55_REG32(0xE000ED0Cu)
#define SCB_AIRCR_SYSRESET     0x05FA0004u

#endif
