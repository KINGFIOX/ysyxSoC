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
#include <stdio.h>
#include <stdlib.h>

#define PSRAM_BASE CONFIG_SOC_PSRAM_BASE
#define PSRAM_SIZE CONFIG_SOC_PSRAM_SIZE

static uint8_t psram_mem[CONFIG_SOC_PSRAM_SIZE];

/**
 * @param addr: addr starts from 0, aligned to 4bytes (offset within psram)
 */
void psram_read(int addr, char *data) {
  /* Check bounds */
  if (addr < 0 || addr >= PSRAM_SIZE) {
    Log("Warning: PSRAM read out of bounds at offset 0x%08x", addr);
    *data = 0;
    return;
  }
  *data = psram_mem[addr];
}

void psram_write(int addr, char data) {
  /* Check bounds */
  if (addr < 0 || addr >= PSRAM_SIZE) {
    Log("Warning: PSRAM read out of bounds at offset 0x%08x", addr);
    return;
  }
  psram_mem[addr] = data;
}

/* Check if address is in PSRAM range */
bool in_psram(paddr_t addr) {
  return addr >= PSRAM_BASE && addr < PSRAM_BASE + PSRAM_SIZE;
}
