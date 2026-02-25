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

// Flash
#define FLASH_LEFT  ((paddr_t)CONFIG_SOC_XIP_FLASH_BASE) /*0x3000_0000*/
#define FLASH_RIGHT ((paddr_t)CONFIG_SOC_XIP_FLASH_BASE + CONFIG_SOC_XIP_FLASH_SIZE - 1)

// SRAM
#define SRAM_LEFT  ((paddr_t)CONFIG_SOC_SRAM_BASE)
#define SRAM_RIGHT ((paddr_t)CONFIG_SOC_SRAM_BASE + CONFIG_SOC_SRAM_SIZE - 1)

// PSRAM
#define PSRAM_LEFT  ((paddr_t)CONFIG_SOC_PSRAM_BASE)
#define PSRAM_RIGHT ((paddr_t)CONFIG_SOC_PSRAM_BASE + CONFIG_SOC_PSRAM_SIZE - 1)

// SDRAM
#define SDRAM_LEFT ((paddr_t)CONFIG_SOC_SDRAM_BASE)
#define SDRAM_RIGHT ((paddr_t)CONFIG_SOC_SDRAM_BASE + CONFIG_SOC_SDRAM_SIZE - 1)

// 复位向量（从 Kconfig 配置）
#define RESET_VECTOR ((paddr_t)CONFIG_SOC_RESET_VECTOR)

word_t paddr_read(paddr_t addr, int len);
void paddr_write(paddr_t addr, int len, word_t data);

#endif
