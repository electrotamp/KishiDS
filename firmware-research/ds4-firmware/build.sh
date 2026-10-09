#!/bin/bash
# Build the Kishi DS4 firmware (links at the vendor app base 0x08003000).
#   ./build.sh          -> kishi_ds4.bin       (release: clean DS4 report, no diagnostics)
#   DIAG=1 ./build.sh   -> kishi_ds4_diag.bin  (flash boot log, register snapshots, raw ADC in report, LED heartbeat)
#   ./build.sh clean    -> remove build outputs
# Uses devkitARM's arm-none-eabi-gcc.
set -e
cd "$(dirname "$0")"
# devkitARM: set DEVKITARM (e.g. /opt/devkitpro/devkitARM or /c/devkitPro/devkitARM); otherwise /c..g/devkitPro/devkitARM is tried. If arm-none-eabi-gcc is already on PATH nothing is needed.
if [ -n "$DEVKITARM" ] && [ -d "$DEVKITARM/bin" ]; then export PATH="$DEVKITARM/bin:$PATH"; else for d in c d e f g; do for n in devkitPro devkitpro; do if [ -d "/$d/$n/devkitARM/bin" ]; then export PATH="/$d/$n/devkitARM/bin:$PATH"; break 2; fi; done; done; fi

if [ "$1" = "clean" ]; then
	rm -f *.o *.elf *.map kishi_ds4.bin kishi_ds4_diag.bin
	exit 0
fi

# Windows installs usually provide "python"; macOS and Linux often only "python3".
PY=$(command -v python || command -v python3)

OUT=kishi_ds4
EXTRA=""
SRCS="main kishi_io config report led live persist ds4_usb"
if [ -n "$DIAG" ]; then OUT=kishi_ds4_diag; EXTRA="-DDIAG"; SRCS="$SRCS diag"; fi

CF="$EXTRA -Os -g3 -std=c99 -mthumb -mcpu=cortex-m0 -msoft-float -DSTM32F0 -I../third_party/libopencm3/include -Wall -Wextra -Wshadow -Wstrict-prototypes -ffunction-sections -fdata-sections"
OBJS=""
for f in $SRCS; do
	arm-none-eabi-gcc $CF -c $f.c -o $f.o
	OBJS="$OBJS $f.o"
done
arm-none-eabi-gcc -Tstm32f072_kishi_app3000.ld -nostartfiles -mthumb -mcpu=cortex-m0 \
	-Wl,--gc-sections -Wl,-Map=$OUT.map $OBJS \
	-L../third_party/libopencm3/lib -Wl,--start-group -lopencm3_stm32f0 -lc -lgcc -lnosys -Wl,--end-group \
	-o $OUT.elf
arm-none-eabi-objcopy -Obinary $OUT.elf $OUT.bin
"$PY" ../tools/finalize_image.py $OUT.bin
arm-none-eabi-size $OUT.elf

"$PY" - "$OUT" <<'PY'
import hashlib, struct, sys
name = sys.argv[1] + ".bin"
d = open(name, "rb").read()
v = struct.unpack_from("<48I", d, 0)
base = 0x08003000
t = sorted(set(x for x in v[1:] if x))
ok = all(base <= (x & ~1) < base + len(d) for x in t)
print(f"{name}: {len(d)} bytes, SP {v[0]:#x}, reset {v[1]:#x}, all vectors in image: {ok}")
print("sha256", hashlib.sha256(d).hexdigest())
PY
