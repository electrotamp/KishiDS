#!/bin/bash
# Host tests: USB (real TinyUSB + usb_ds4.c, fake hardware driver) and ADC gain calibration, with the host C compiler.
set -e
cd "$(dirname "$0")/.."
CC=${CC:-$(command -v cc || command -v gcc || command -v clang)}
T=../third_party/tinyusb/src
S=../ds4-firmware
# diag.c comes along for the `diag` struct; --gc-sections drops its fault path (it needs bootloader.c, target only).
"$CC" -std=c11 -O1 -g -DKCFG_HOST_TEST=1 -Wall -Wextra -Wno-unused-parameter -Wno-missing-field-initializers \
	-ffunction-sections -Wl,--gc-sections \
	-I. -I$S -I$T \
	tests/test_usb_host.c usb_ds4.c diag.c $S/config.c $S/live.c $S/ds4_usb.c \
	$T/tusb.c $T/common/tusb_fifo.c $T/device/usbd.c $T/class/hid/hid_device.c \
	-o tests/test_usb_host.exe
./tests/test_usb_host.exe
# ADC gain calibration: integer version vs NXP's float algorithm (adc.c compiled for the host; its register code is
# never called here).
"$CC" -std=c11 -O1 -Wall -Wextra -Wno-int-to-pointer-cast -ffunction-sections -Wl,--gc-sections -I. -I$S \
	tests/test_adc_gain.c adc.c diag.c -o tests/test_adc_gain.exe
./tests/test_adc_gain.exe
