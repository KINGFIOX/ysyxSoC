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

// MROM 接口
extern bool in_mrom(paddr_t addr);
extern void mrom_read(int addr, int *data);
extern void mrom_write(paddr_t addr, int len, word_t data);

static void out_of_bound(paddr_t addr) {
  panic("address = " FMT_PADDR " is out of bound at pc = " FMT_WORD,
        addr, cpu.pc);
}

void init_mem() {
  // MROM 在 monitor.c 中通过 init_mrom(img_file) 初始化
}

word_t paddr_read(paddr_t addr, int len) {
  if (in_mrom(addr)) {
    int data;
    mrom_read((int)addr, &data);
    word_t mask = (len == 4) ? 0xFFFFFFFF : ((1u << (len * 8)) - 1);
    return (word_t)data & mask;
  }
  IFDEF(CONFIG_DEVICE, return mmio_read(addr, len));
  out_of_bound(addr);
  return 0;
}

void paddr_write(paddr_t addr, int len, word_t data) {
  if (in_mrom(addr)) { mrom_write(addr, len, data); return; }
  IFDEF(CONFIG_DEVICE, mmio_write(addr, len, data); return);
  out_of_bound(addr);
}
