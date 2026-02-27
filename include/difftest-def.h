#pragma once

/**
 * difftest-def.h - 供 spike-diff 等 difftest 参考实现使用的定义
 * 仅包含必要类型与宏，避免引入 log 等 C++20 依赖（spike 使用 C++17）
 */
#include <cstdint>
#include <generated/autoconf.h>
#include <npc/macro.hh>

#if CONFIG_SOC_XIP_FLASH_BASE + CONFIG_SOC_XIP_FLASH_SIZE > 0x100000000ul ||   \
    CONFIG_SOC_SRAM_BASE + CONFIG_SOC_SRAM_SIZE > 0x100000000ul
#define PMEM64 1
#endif

using word_t = uint32_t;
using sword_t = int32_t;
using paddr_t = MUXDEF(PMEM64, uint64_t, uint32_t);

#define __EXPORT __attribute__((visibility("default")))
enum { DIFFTEST_TO_DUT, DIFFTEST_TO_REF };
