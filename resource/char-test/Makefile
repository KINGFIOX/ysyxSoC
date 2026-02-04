CROSS_COMPILE ?= riscv32-unknown-linux-gnu-
CC = $(CROSS_COMPILE)gcc
OBJCOPY = $(CROSS_COMPILE)objcopy
OBJDUMP = $(CROSS_COMPILE)objdump

CFLAGS = -nostdlib -nostartfiles -march=rv32i -mabi=ilp32 -ffreestanding
LDFLAGS = -T mrom.ld

APP = char-test

all: $(APP).elf $(APP).bin $(APP).txt

$(APP).elf: $(APP).c mrom.ld
	$(CC) $(CFLAGS) $(LDFLAGS) -o $@ $(APP).c

$(APP).txt: $(APP).elf
	$(OBJDUMP) -d $< > $@

$(APP).bin: $(APP).elf $(APP).txt
	$(OBJCOPY) -O binary --only-section=.text $< $@

run: $(APP).bin
	$(MAKE) -C $(NPC_HOME) run IMG=$(CURDIR)/$(APP).bin

.PHONY: clean run
clean:
	rm -f $(APP).elf $(APP).bin $(APP).txt
