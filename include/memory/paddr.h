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

#ifndef __MEMORY_PADDR_H__
#define __MEMORY_PADDR_H__

#include <common.h>

// MROM 地址范围
#define MROM_LEFT  ((paddr_t)CONFIG_SOC_MROM_BASE)
#define MROM_RIGHT ((paddr_t)CONFIG_SOC_MROM_BASE + CONFIG_SOC_MROM_SIZE - 1)

// SRAM 地址范围
#define SRAM_LEFT  ((paddr_t)CONFIG_SOC_SRAM_BASE)
#define SRAM_RIGHT ((paddr_t)CONFIG_SOC_SRAM_BASE + CONFIG_SOC_SRAM_SIZE - 1)

// 复位向量：从 MROM 开始
#define RESET_VECTOR (MROM_LEFT)

word_t paddr_read(paddr_t addr, int len);
void paddr_write(paddr_t addr, int len, word_t data);

#endif
