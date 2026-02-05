CROSS_COMPILE ?= riscv32-unknown-linux-gnu-
CC = $(CROSS_COMPILE)gcc
OBJCOPY = $(CROSS_COMPILE)objcopy
OBJDUMP = $(CROSS_COMPILE)objdump

# UART 时钟频率 (Hz)，仿真用 50M，流片可用 100M~1G
UART_CLOCK_FREQ ?= 1000000000

CFLAGS = -nostdlib -nostartfiles -march=rv32i -mabi=ilp32 -ffreestanding -static -fno-pic -fno-pie
CFLAGS += -DUART_CLOCK_FREQ=$(UART_CLOCK_FREQ)
LDFLAGS = -T linker.ld

APP = char-test

all: $(APP).elf $(APP).bin $(APP).txt

$(APP).elf: start.S $(APP).c linker.ld
	$(CC) $(CFLAGS) $(LDFLAGS) -o $@ start.S $(APP).c

$(APP).txt: $(APP).elf
	$(OBJDUMP) -d $< > $@

$(APP).bin: $(APP).elf $(APP).txt
	$(OBJCOPY) -O binary --only-section=.text $< $@

run: $(APP).bin
	$(MAKE) -C $(NPC_HOME) run IMG=$(CURDIR)/$(APP).bin

.PHONY: clean run
clean:
	rm -f $(APP).elf $(APP).bin $(APP).txt
