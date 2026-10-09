#!/bin/bash
# Compile and run the host unit tests (needs any host C compiler: gcc or clang).
set -e
cd "$(dirname "$0")/.."
CC=${CC:-$(command -v gcc || command -v clang || command -v x86_64-w64-mingw32-gcc)}
"$CC" -std=c11 -Wall -Wextra -Werror -I. tests/test_report.c report.c config.c ds4_usb.c -o tests/test_report.exe
./tests/test_report.exe
"$CC" -std=c11 -Wall -Wextra -Werror -DKCFG_HOST_TEST=1 -I. tests/test_live.c live.c config.c -o tests/test_live.exe
./tests/test_live.exe
# Same tests with the Kishi V2 Pro's bootloader command compiled in.
"$CC" -std=c11 -Wall -Wextra -Werror -DKCFG_HOST_TEST=1 -DLIVE_HAS_BOOTLOADER -I. tests/test_live.c live.c config.c \
	-o tests/test_live_boot.exe
./tests/test_live_boot.exe
