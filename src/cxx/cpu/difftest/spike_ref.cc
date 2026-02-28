/***************************************************************************************
* Copyright (c) 2014-2024 Zihao Yu, Nanjing University
*
* NEMU is licensed under Mulan PSL v2.
* You can use this software according to the terms and conditions of the Mulan PSL v2.
* You may obtain a copy of Mulan PSL v2 at:
*          http://license.coscl.org.cn/MulanPSL2
*
* THIS SOFTWARE IS PROVIDED ON AN "AS IS" BASIS, WITHOUT WARRANTIES OF ANY KIND,
* EITHER EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO NON-INFRINGEMENT,
* MERCHANTABILITY OR FIT FOR A PARTICULAR PURPOSE.
*
* See the Mulan PSL v2 for more details.
***************************************************************************************/

#include "mmu.h"
#include "sim.h"
#include <cassert>
#include <cstdint>
#include <difftest-def.h>

#define NR_GPR 32

static std::vector<std::pair<reg_t, abstract_device_t*>> difftest_plugin_devices;
static std::vector<std::string> difftest_htif_args;
// spike mem_t 要求大小是 4 KiB 的倍数
#define ALIGN_4K(size) (((size) + 0xFFF) & ~0xFFF)

// NPC 物理内存和 MMIO 区域
// 必须添加所有可能访问的地址区域，否则 spike 会抛出 access fault
static std::vector<std::pair<reg_t, mem_t*>> difftest_mem = {
    // 主存储区域
    std::make_pair(reg_t(CONFIG_SOC_MROM_BASE), new mem_t(ALIGN_4K(CONFIG_SOC_MROM_SIZE))),
    std::make_pair(reg_t(CONFIG_SOC_SRAM_BASE), new mem_t(ALIGN_4K(CONFIG_SOC_SRAM_SIZE))),
    // MMIO 设备区域 (简化处理，只添加内存映射，不实现具体功能)
    std::make_pair(reg_t(CONFIG_SOC_UART_BASE), new mem_t(ALIGN_4K(CONFIG_SOC_UART_SIZE))),
#ifdef CONFIG_SOC_GPIO_BASE
    std::make_pair(reg_t(CONFIG_SOC_GPIO_BASE), new mem_t(ALIGN_4K(CONFIG_SOC_GPIO_SIZE))),
#endif
#ifdef CONFIG_SOC_KEYBOARD_BASE
    std::make_pair(reg_t(CONFIG_SOC_KEYBOARD_BASE), new mem_t(ALIGN_4K(CONFIG_SOC_KEYBOARD_SIZE))),
#endif
#ifdef CONFIG_SOC_VGA_BASE
    std::make_pair(reg_t(CONFIG_SOC_VGA_BASE), new mem_t(ALIGN_4K(CONFIG_SOC_VGA_SIZE))),
#endif
#ifdef CONFIG_SOC_SPI_CTRL_BASE
    std::make_pair(reg_t(CONFIG_SOC_SPI_CTRL_BASE), new mem_t(ALIGN_4K(CONFIG_SOC_SPI_CTRL_SIZE))),
#endif
#ifdef CONFIG_SOC_XIP_FLASH_BASE
    std::make_pair(reg_t(CONFIG_SOC_XIP_FLASH_BASE), new mem_t(ALIGN_4K(CONFIG_SOC_XIP_FLASH_SIZE))),
#endif
#ifdef CONFIG_SOC_PSRAM_BASE
    std::make_pair(reg_t(CONFIG_SOC_PSRAM_BASE), new mem_t(ALIGN_4K(CONFIG_SOC_PSRAM_SIZE))),
#endif
#ifdef CONFIG_SOC_SDRAM_BASE
    std::make_pair(reg_t(CONFIG_SOC_SDRAM_BASE), new mem_t(ALIGN_4K(CONFIG_SOC_SDRAM_SIZE))),
#endif
};
static debug_module_config_t difftest_dm_config = {
  .progbufsize = 2,
  .max_sba_data_width = 0,
  .require_authentication = false,
  .abstract_rti = 0,
  .support_hasel = true,
  .support_abstract_csr_access = true,
  .support_abstract_fpr_access = true,
  .support_haltgroups = true,
  .support_impebreak = true
};

struct diff_context_t {
  word_t gpr[32];
  word_t pc;
};

static sim_t* s = NULL;
static processor_t *p = NULL;
static state_t *state = NULL;

void sim_t::diff_init(int port) {
  (void)port;
  p = get_core("0");
  state = p->get_state();
  // 设置初始 PC 为复位向量地址
  state->pc = CONFIG_SOC_RESET_VECTOR;
}

void sim_t::diff_step(uint64_t n) {
  step(n);
}

void sim_t::diff_get_regs(void* diff_context) {
  struct diff_context_t* ctx = (struct diff_context_t*)diff_context;
  for (int i = 0; i < NR_GPR; i++) {
    ctx->gpr[i] = state->XPR[i];
  }
  ctx->pc = state->pc;
}

void sim_t::diff_set_regs(void* diff_context) {
  struct diff_context_t* ctx = (struct diff_context_t*)diff_context;
  for (int i = 0; i < NR_GPR; i++) {
    state->XPR.write(i, (sword_t)ctx->gpr[i]);
  }
  state->pc = ctx->pc;
}

void sim_t::diff_memcpy(reg_t dest, void* src, size_t n) {
  mmu_t* mmu = p->get_mmu();
  for (size_t i = 0; i < n; i++) {
    mmu->store<uint8_t>(dest+i, *((uint8_t*)src+i));
  }
}

extern "C" {

void difftest_memcpy(paddr_t addr, void *buf, size_t n, bool direction) {
  if (direction == DIFFTEST_TO_REF) {
    s->diff_memcpy(addr, buf, n);
  } else {
    assert(0);
  }
}

void difftest_regcpy(void* dut, bool direction) {
  if (direction == DIFFTEST_TO_REF) {
    s->diff_set_regs(dut);
  } else {
    s->diff_get_regs(dut);
  }
}

void difftest_exec(uint64_t n) {
  s->diff_step(n);
}

void difftest_init(int port) {
  difftest_htif_args.push_back("");
  const char *isa = "RV32IMAFDC";
  cfg_t *cfg = new cfg_t(/*default_initrd_bounds=*/std::make_pair((reg_t)0, (reg_t)0),
            /*default_bootargs=*/nullptr,
            /*default_isa=*/isa,
            /*default_priv=*/DEFAULT_PRIV,
            /*default_varch=*/DEFAULT_VARCH,
            /*default_misaligned=*/false,
            /*default_endianness*/endianness_little,
            /*default_pmpregions=*/16,
            /*default_mem_layout=*/std::vector<mem_cfg_t>(),
            /*default_hartids=*/std::vector<size_t>(1),
            /*default_real_time_clint=*/false,
            /*default_trigger_count=*/4);
  s = new sim_t(cfg, false,
      difftest_mem, difftest_plugin_devices, difftest_htif_args,
      difftest_dm_config, nullptr, false, NULL,
      false,
      NULL,
      true);
  s->diff_init(port); // 初始化 state, 因此设置 mstatus 要放在这句话之后
}

void difftest_raise_intr(uint64_t NO, uint64_t tval) {
  insn_trap_t t(NO, false, tval);
  p->take_trap_public(t, state->pc);
}

}
