# Kishi 0290 DS4-style firmware.  Linked at the vendor app base 0x08003000.
# Needs arm-none-eabi-gcc on PATH (devkitARM: E:/devkitpro/devkitARM/bin).
PREFIX ?= arm-none-eabi-
CC := $(PREFIX)gcc
OBJCOPY := $(PREFIX)objcopy
SIZE := $(PREFIX)size

OPENCM3_DIR := ../third_party/libopencm3
TARGET := kishi_ds4
OBJS := main.o kishi_io.o

CFLAGS := -Os -g3 -std=c99 -mthumb -mcpu=cortex-m0 -msoft-float
CFLAGS += -DSTM32F0 -I$(OPENCM3_DIR)/include
CFLAGS += -Wall -Wextra -Wshadow -Wstrict-prototypes
CFLAGS += -ffunction-sections -fdata-sections

LDFLAGS := -Tstm32f072_kishi_app3000.ld -nostartfiles -mthumb -mcpu=cortex-m0
LDFLAGS += -Wl,--gc-sections -Wl,-Map=$(TARGET).map
LDLIBS := -L$(OPENCM3_DIR)/lib -Wl,--start-group -lopencm3_stm32f0 -lc -lgcc -lnosys -Wl,--end-group

all: $(TARGET).bin

$(TARGET).elf: $(OBJS) stm32f072_kishi_app3000.ld
	$(CC) $(LDFLAGS) $(OBJS) $(LDLIBS) -o $@

$(TARGET).bin: $(TARGET).elf
	$(OBJCOPY) -Obinary $< $@
	$(SIZE) $<

%.o: %.c kishi_io.h
	$(CC) $(CFLAGS) -c $< -o $@

clean:
	-$(RM) $(OBJS) $(TARGET).elf $(TARGET).bin $(TARGET).map

.PHONY: all clean
