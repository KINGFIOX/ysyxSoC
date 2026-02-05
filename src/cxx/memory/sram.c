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

#include <common.h>
#include <memory/host.h>

#define SRAM_BASE CONFIG_SOC_SRAM_BASE
#define SRAM_SIZE CONFIG_SOC_SRAM_SIZE

static uint8_t sram[SRAM_SIZE] PG_ALIGN = {};

static uint8_t *sram_guest_to_host(paddr_t paddr) { 
  return sram + paddr - SRAM_BASE; 
}

bool in_sram(paddr_t addr) {
  return addr >= SRAM_BASE && addr < SRAM_BASE + SRAM_SIZE;
}

void init_sram() {
  Log("sram area [" FMT_PADDR ", " FMT_PADDR "]",
      (paddr_t)SRAM_BASE, (paddr_t)(SRAM_BASE + SRAM_SIZE - 1));
}

word_t sram_read(paddr_t addr, int len) {
  return host_read(sram_guest_to_host(addr), len);
}

void sram_write(paddr_t addr, int len, word_t data) {
  host_write(sram_guest_to_host(addr), len, data);
}
