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

/**
 * SRAM 访问模块 - 通过 Verilator 内存指针直接访问 AXI4RAM
 *
 * AXI4RAM 使用 Chisel SeqMem 实现，在 Verilator 仿真中生成数组：
 *   top->rootp->NPCSoC__DOT__dut__DOT__asic__DOT__axi4ram__DOT__mem_ext__DOT__Memory
 *
 * 这是一个 2048 x 32bit 的数组（8KB），对应地址 0x0f000000 - 0x0f001fff
 */

#include <common.h>

#define SRAM_BASE CONFIG_SOC_SRAM_BASE
#define SRAM_SIZE CONFIG_SOC_SRAM_SIZE
#define SRAM_WORDS (SRAM_SIZE / 4) // 2048 words

bool in_sram(paddr_t addr) { return addr >= SRAM_BASE && addr < SRAM_BASE + SRAM_SIZE; }

static uint8_t *sram_ptr = NULL;

void init_sram(uint8_t *verilator_sram_ptr) {
  sram_ptr = verilator_sram_ptr;
  Log("sram area [" FMT_PADDR ", " FMT_PADDR "], verilator ptr = %p",
      (paddr_t)SRAM_BASE, (paddr_t)(SRAM_BASE + SRAM_SIZE - 1), sram_ptr);
}

word_t sram_read(paddr_t addr, int len) {
  if (unlikely(sram_ptr == NULL)) {
    Log("Warning: sram_ptr not initialized");
    return 0xdeadbeef;
  }
  uint32_t offset = (uint32_t)addr - SRAM_BASE;
  if (unlikely(offset + len > SRAM_SIZE)) {
    Log("Warning: sram read out of range: " FMT_PADDR, addr);
    return 0xdeadbeef;
  }
  word_t data = 0;
  for (int i = 0; i < len; i++) {
    data |= (word_t)sram_ptr[offset + i] << (i * 8);
  }
  return data;
}

void sram_write(paddr_t addr, int len, word_t data) {
  if (sram_ptr == NULL) {
    Log("Warning: sram_ptr not initialized");
    return;
  }

  uint32_t offset = (uint32_t)addr - SRAM_BASE;
  if (offset + len > SRAM_SIZE) {
    Log("Warning: sram write out of range: " FMT_PADDR, addr);
    return;
  }

  // 直接按字节写入
  for (int i = 0; i < len; i++) {
    sram_ptr[offset + i] = (data >> (i * 8)) & 0xFF;
  }
}
