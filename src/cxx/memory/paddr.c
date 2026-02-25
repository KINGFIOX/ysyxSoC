/***************************************************************************************
 * Copyright (c) 2014-2024 Zihao Yu, Nanjing University
 *
 * NPC is licensed under Mulan PSL v2.
 * You can use this software according to the terms and conditions of the Mulan
 *PSL v2. You may obtain a copy of Mulan PSL v2 at:
 *          http://license.coscl.org.cn/MulanPSL2
 *
 * THIS SOFTWARE IS PROVIDED ON AN "AS IS" BASIS, WITHOUT WARRANTIES OF ANY
 *KIND, EITHER EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO
 *NON-INFRINGEMENT, MERCHANTABILITY OR FIT FOR A PARTICULAR PURPOSE.
 *
 * See the Mulan PSL v2 for more details.
 ***************************************************************************************/

#include <device/mmio.h>
#include <isa.h>
#include <memory/paddr.h>

// SRAM 接口（直接访问 Verilator 中的 AXI4RAM 内存）
extern bool in_sram(paddr_t addr);
extern word_t sram_read(paddr_t addr, int len);
extern void sram_write(paddr_t addr, int len, word_t data);

// Flash 接口
extern bool in_flash(paddr_t addr);
extern void flash_read(int addr, char *data);

// PSRAM 接口
extern bool in_psram(paddr_t addr);
extern void psram_read(int addr, char *data);
extern void psram_write(int addr, char data);

// SDRAM 接口
extern bool in_sdram(paddr_t addr);
extern void sdram_read(int addr, char *data);
extern void sdram_write(int addr, char data);

static void out_of_bound(paddr_t addr) {
  panic("address = " FMT_PADDR " is out of bound at pc = " FMT_WORD,
        addr, cpu.pc);
}

void init_mem() {
  // Flash 在 monitor.c 中通过 init_flash(img_file) 初始化
}

word_t paddr_read(paddr_t addr, int len) {
  if (in_flash(addr)) {
    word_t result = 0;
    for (int i = 0; i < len; i++) {
      char byte;
      flash_read((int)(addr - FLASH_LEFT + i), &byte);
      result |= ((word_t)(uint8_t)byte) << (i * 8);  // 小端序
    }
    return result;
  }
  if (in_psram(addr)) {
    word_t result = 0;
    for (int i = 0; i < len; i++) {
      char byte;
      psram_read((int)(addr - PSRAM_LEFT + i), &byte);
      result |= ((word_t)(uint8_t)byte) << (i * 8);  // 小端序
    }
    return result;
  }
  if (in_sdram(addr)) {
    word_t result = 0;
    for (int i = 0; i < len; i++) {
      char byte;
      sdram_read((int)(addr - SDRAM_LEFT + i), &byte);
      result |= ((word_t)(uint8_t)byte) << (i * 8);  // 小端序
    }
    return result;
  }
  if (in_sram(addr)) {
    return sram_read(addr, len);
  }
  IFDEF(CONFIG_DEVICE, return mmio_read(addr, len));
  out_of_bound(addr);
  return 0;
}

void paddr_write(paddr_t addr, int len, word_t data) {
  if (in_flash(addr)) { panic("Flash is read-only, cannot write to address " FMT_PADDR, addr); }
  if (in_psram(addr)) {
    for (int i = 0; i < len; i++) {
      psram_write((int)(addr - PSRAM_LEFT + i), (char)(data >> (i * 8)));  // 小端序
    }
    return;
  }
  if (in_sdram(addr)) {
    for (int i = 0; i < len; i++) {
      sdram_write((int)(addr - SDRAM_LEFT + i), (char)(data >> (i * 8)));  // 小端序
    }
    return;
  }
  if (in_sram(addr)) { sram_write(addr, len, data); return; }
  IFDEF(CONFIG_DEVICE, mmio_write(addr, len, data); return);
  out_of_bound(addr);
}
