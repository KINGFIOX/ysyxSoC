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
extern void flash_read(int addr, int *data);

static void out_of_bound(paddr_t addr) {
  panic("address = " FMT_PADDR " is out of bound at pc = " FMT_WORD,
        addr, cpu.pc);
}

void init_mem() {
  // Flash 在 monitor.c 中通过 init_flash(img_file) 初始化
}

word_t paddr_read(paddr_t addr, int len) {
  if (in_flash(addr)) {
    int data;
    flash_read((int)(addr - FLASH_LEFT), &data);
    word_t mask = (len == 4) ? 0xFFFFFFFF : ((1u << (len * 8)) - 1);
    return (word_t)data & mask;
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
  if (in_sram(addr)) { sram_write(addr, len, data); return; }
  IFDEF(CONFIG_DEVICE, mmio_write(addr, len, data); return);
  out_of_bound(addr);
}
