# RISCV environment variable must be set
BRAM_DIR := $(dir $(abspath $(lastword $(MAKEFILE_LIST))))
BROM_DIR := $(BRAM_DIR)/..
BUILD_DIR := $(BRAM_DIR)/build
DRIVER_DIR := $(BROM_DIR)/driver

CC=$(RISCV)/bin/riscv64-unknown-elf-gcc
OBJCOPY=$(RISCV)/bin/riscv64-unknown-elf-objcopy
OBJDUMP=$(RISCV)/bin/riscv64-unknown-elf-objdump
CFLAGS=-march=rv64ima -mcmodel=medany -O2 -std=gnu11 -Wall -nostartfiles 
CFLAGS+= -fno-common -g -DENTROPY=0 -mabi=lp64 -DNONSMP_HART=0 -ffunction-sections -fdata-sections
CFLAGS+= -I $(BROM_DIR)/include -I $(DRIVER_DIR) -I../
CFLAGS+= -ffunction-sections -fdata-sections 
LFLAGS=-static -Wl,-static -nostdlib -L $(BROM_DIR)/linker -T bram.elf.lds

#PBUS_CLK ?= 1000000 # default to 1MHz but really should be overridden
PBUS_CLK ?= 50000000 # default to 1MHz but really should be overridden

c_source = $(BRAM_DIR)/head.S $(BRAM_DIR)/syscalls.c  
c_source+= $(DRIVER_DIR)/uart.c $(DRIVER_DIR)/spi.c $(DRIVER_DIR)/diskio.c $(DRIVER_DIR)/ff.c $(DRIVER_DIR)/elf.c $(DRIVER_DIR)/pfc.c $(DRIVER_DIR)/gpio.c
c_source+= $(BROM_DIR)/kprintf.c 


TARGETS = jump dram dram_2 dram_heay dram_check_deadlock baresd fat32txt fat32bin fat32elf testpfc testled \
          evset_threshold evset_ge evset_ct evset_ppp

RISCV_FILES =  $(addsuffix .riscv, $(TARGETS))
DUMP_FILES  =  $(addsuffix .dump,  $(TARGETS))
BIN_FILES   =  $(addsuffix .bin,   $(TARGETS))
MEM_FILES   =  $(addsuffix .mem,   $(TARGETS))   ##fpga need

$(RISCV_FILES): %.riscv: %.c $(c_source)
	mkdir -p $(BUILD_DIR)
	$(CC) $(CFLAGS) -DTL_CLK="$(PBUS_CLK)" $(LFLAGS) -o $(BUILD_DIR)/$@ $(c_source) $<
	ln -fs $(BUILD_DIR)/$@ ./$@

$(DUMP_FILES): %.dump: %.riscv
	$(OBJDUMP) -D -S -l $< > $(BUILD_DIR)/$@

$(BIN_FILES): %.bin: %.riscv
	$(OBJCOPY) -O binary --change-addresses=-0x10000 $< $(BUILD_DIR)/$@

#bootram start  0x20000 - 0x30000 : 131072  - 196608
#verilog: reg [63:0] bootram [0: 8191]
#elf2hex <width> <depth> <elf_file> [base]
$(MEM_FILES): %.mem: %.riscv
	mkdir -p $(BUILD_DIR)
	elf2hex  8 8192 $< 131072 > $(BUILD_DIR)/$@


clean:
	rm -rf $(BUILD_DIR)
.PHONY: clean
