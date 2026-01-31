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

#ifndef __RISCV_REG_H__
#define __RISCV_REG_H__

#include <common.h>

enum {
  MSTATUS = 0x0300,
  MTVEC = 0x0305,
  MEPC = 0x0341,
  MCAUSE = 0x0342,
  MTVAL = 0x0343,
  MVENDORID = 0x0F11,
  MARCHID   = 0x0F12,
};
static inline int check_csr_idx(int idx) {
  IFDEF(CONFIG_RT_CHECK, assert( idx == MSTATUS || idx == MTVEC || idx == MEPC || idx == MCAUSE || idx == MTVAL || idx == MVENDORID || idx == MARCHID ) );
  return idx;
}
#define csr(idx) (cpu.csr[check_csr_idx(idx)])

static inline int check_reg_idx(int idx) {
  IFDEF(CONFIG_RT_CHECK, assert(idx >= 0 && idx < MUXDEF(CONFIG_RVE, 16, 32)));
  return idx;
}

#define gpr(idx) (cpu.gpr[check_reg_idx(idx)])

static inline const char *reg_name(int idx) {
  extern const char *regs[];
  return regs[check_reg_idx(idx)];
}

#endif
