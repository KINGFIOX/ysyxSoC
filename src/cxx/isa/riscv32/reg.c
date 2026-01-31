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

#include "local-include/reg.h"
#include <isa.h>

const char *regs[] = {"$0", "ra", "sp",  "gp",  "tp", "t0", "t1", "t2",
                      "s0", "s1", "a0",  "a1",  "a2", "a3", "a4", "a5",
                      "a6", "a7", "s2",  "s3",  "s4", "s5", "s6", "s7",
                      "s8", "s9", "s10", "s11", "t3", "t4", "t5", "t6"};

void isa_reg_display() {
  for (int i = 0; i < 32; i++) {
    printf("%s:\t" FMT_WORD "\n", regs[i], cpu.gpr[i]);
  }
  printf("pc:\t" FMT_WORD "\n", cpu.pc);
}

const char *csrs[] = {
  [MSTATUS] = "mstatus",
  [MTVEC] = "mtvec",
  [MEPC] = "mepc",
  [MCAUSE] = "mcause",
  [MTVAL] = "mtval",
  [MVENDORID] = "mvendorid",
  [MARCHID] = "marchid",
};

word_t isa_reg_str2val(const char *s, bool *success) {
  if (success) { *success = true; }

  if (strcmp(s, "pc") == 0) {
    return cpu.pc;
  }

  for (int i = 0; i < 32; i++) {
    if (strcmp(s, regs[i]) == 0) {
      return cpu.gpr[i];
    }
  }

  // csrs
  if (strcmp(s, csrs[MSTATUS]) == 0) { return cpu.csr[MSTATUS]; }
  if (strcmp(s, csrs[MTVEC]) == 0) { return cpu.csr[MTVEC]; }
  if (strcmp(s, csrs[MEPC]) == 0) { return cpu.csr[MEPC]; }
  if (strcmp(s, csrs[MCAUSE]) == 0) { return cpu.csr[MCAUSE]; }
  if (strcmp(s, csrs[MTVAL]) == 0) { return cpu.csr[MTVAL]; }
  if (strcmp(s, csrs[MVENDORID]) == 0) { return cpu.csr[MVENDORID]; }
  if (strcmp(s, csrs[MARCHID]) == 0) { return cpu.csr[MARCHID]; }

  if (success) {
    *success = false;
  }
  return 0;
}
