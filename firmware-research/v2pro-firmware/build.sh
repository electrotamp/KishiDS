#!/bin/bash
# Build the Kishi V2 Pro DS4 firmware skeleton (links at Razer's application base 0x8000).
#   ./build.sh         -> kishi_v2pro_ds4.bin / .hex
#   ./build.sh clean   -> remove build outputs
#   EXTRA_CFLAGS="..." ./build.sh [diag]   -> extra defines, e.g. which fault detail a diagnostic build signals through
#                                            the timing channel: -DDIAG_SIGNAL_VECTOR, -DDIAG_SIGNAL_FAULT_CLASS,
#                                            -DDIAG_SIGNAL_BFAR (default: the last DIAG_STEP; see diag.c)
# Needs only arm-none-eabi-gcc on PATH (Arm GNU Toolchain, devkitARM or Homebrew's): the build is freestanding and brings
# its own memcpy/memset/memcmp (libc/), so no newlib is required.
# Shared, hardware-independent sources (config, report, live, ds4_usb) are compiled from ../ds4-firmware unchanged.
# USB: TinyUSB (../third_party/tinyusb, pinned submodule: git submodule update --init firmware-research/third_party/tinyusb).
set -e
cd "$(dirname "$0")"

OUT=kishi_v2pro_ds4
DIAG="${EXTRA_CFLAGS:-}"
if [ "$1" = "clean" ]; then
	rm -f *.o *.elf *.map $OUT.bin $OUT.hex ${OUT}_diag.bin ${OUT}_diag.hex
	exit 0
fi
# ./build.sh diag -> kishi_v2pro_ds4_diag: reports start-up problems through Razer's bootloader (diag.h).
if [ "$1" = "diag" ]; then
	OUT=${OUT}_diag
	DIAG="-DV2PRO_DIAG ${EXTRA_CFLAGS:-}"
fi

SHARED=../ds4-firmware
TUSB=../third_party/tinyusb/src
if [ ! -f $TUSB/tusb.c ]; then echo "TinyUSB missing: git submodule update --init firmware-research/third_party/tinyusb"; exit 1; fi
CPU="-mthumb -mcpu=cortex-m33 -mfloat-abi=soft"
CF="$CPU -Os -g3 -std=c99 -ffreestanding -I. -Ilibc -I$SHARED -I$TUSB -DKCFG_SAVED_ADDR=0x0001E000u -DKCFG_SAVED_VIA_BOARD -DLIVE_HAS_BOOTLOADER -DLIVE_BOARD_CHUNKS=8 $DIAG \
	-Wall -Wextra -Wshadow -Wstrict-prototypes -ffunction-sections -fdata-sections"

OBJS=""
for f in startup main bootloader diag clock adc flash board_v2pro led_v2pro i2c_lpc55 haptics_v2pro usblog_v2pro persist usb_ds4 libc/string; do
	o=$(basename $f).o
	arm-none-eabi-gcc $CF -c $f.c -o $o
	OBJS="$OBJS $o"
done
# config.c takes the V2 Pro's own default calibration (board_defaults.h); later designated initialisers override the
# V1's on purpose, hence -Wno-override-init.
BOARD_DEFAULTS="-DKCFG_BOARD_OVERRIDES_HEADER=\"board_defaults.h\" -Wno-override-init"
for f in config report live ds4_usb; do
	arm-none-eabi-gcc $CF $BOARD_DEFAULTS -c $SHARED/$f.c -o shared_$f.o
	OBJS="$OBJS shared_$f.o"
done
# TinyUSB is third-party code: built with its own warnings, not ours.
TCF="$CPU -Os -g3 -std=c99 -ffreestanding -I. -Ilibc -I$TUSB -ffunction-sections -fdata-sections"
for f in tusb common/tusb_fifo device/usbd class/hid/hid_device portable/nxp/lpc_ip3511/dcd_lpc_ip3511; do
	o=tusb_$(basename $f).o
	arm-none-eabi-gcc $TCF -c $TUSB/$f.c -o $o
	OBJS="$OBJS $o"
done

# --no-warn-rwx-segments: .data holds .ramfunc (flash.c), so the RAM segment is meant to be executable.
arm-none-eabi-gcc $CPU -Tv2pro_app8000.ld -nostartfiles -Wl,--gc-sections -Wl,--no-warn-rwx-segments -Wl,-Map=$OUT.map $OBJS \
	-nostdlib -lgcc -o $OUT.elf
# An empty .data keeps a RAM load address (see the linker script); leave it out of the images then.
DROP=""
if [ "$(arm-none-eabi-size -A $OUT.elf | awk '$1 == ".data" {print $2}')" = "0" ]; then DROP="-R .data"; fi
arm-none-eabi-objcopy -Obinary --gap-fill 0xFF $DROP $OUT.elf $OUT.bin
# Stamp the config block's CRC (as the V1 build does); without it the block is invalid, the firmware falls back to
# built-in defaults, and saved settings would be written without the block magic and rejected at the next boot.
python3 ../tools/finalize_image.py $OUT.bin --base 0x8000 --limit 0x1EC00
# The .hex comes from the gap-filled .bin so it is one contiguous range: v2pro_dfu.py refuses images with holes, and on the
# LPC55 a page that was erased but never programmed faults when read, so every page in our span gets programmed.
arm-none-eabi-objcopy -Ibinary -Oihex --change-addresses 0x8000 $OUT.bin $OUT.hex
arm-none-eabi-size $OUT.elf

python3 - "$OUT" <<'PY'
import hashlib, struct, sys
d = open(sys.argv[1] + ".bin", "rb").read()
base, end = 0x8000, 0x1EC00   # Razer's 2.2 image spans 0x8000..0x1EBFF
v = struct.unpack_from("<16I", d, 0)
t = sorted(set(x for x in v[1:] if x))
ok = all(base <= (x & ~1) < base + len(d) for x in t)
print(f"{sys.argv[1]}.bin: {len(d)} bytes at {base:#x}..{base + len(d) - 1:#x}, SP {v[0]:#x}, reset {v[1]:#x}, "
      f"vectors in image: {ok}, inside Razer's span: {base + len(d) <= end}")
assert ok and base + len(d) <= end
print("sha256", hashlib.sha256(d).hexdigest())
PY
