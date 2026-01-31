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

#include "../local-include/reg.h"
#include <cpu/difftest.h>
#include <isa.h>
#include <utils.h>

// 打印一行寄存器对比
static void print_reg_row(const char *name, word_t ref_val, word_t npc_val) {
  bool is_diff = (ref_val != npc_val);
  
  if (is_diff) {
    // 差异行：寄存器名红色，REF绿色，NPC红色，状态红色
    printf("| %s%-4s%s | %s" FMT_WORD "%s | %s" FMT_WORD "%s | %sMISMATCH%s |\n",
           ANSI_FG_RED, name, ANSI_NONE,
           ANSI_FG_GREEN, ref_val, ANSI_NONE,
           ANSI_FG_RED, npc_val, ANSI_NONE,
           ANSI_FG_RED, ANSI_NONE);
  } else {
    printf("| %-4s | " FMT_WORD " | " FMT_WORD " | OK       |\n",
           name, ref_val, npc_val);
  }
}

// 打印寄存器对比表格
static void print_diff_table(const CPU_state *ref_r, vaddr_t pc) {
  int num_regs = MUXDEF(CONFIG_RVE, 16, 32);
  
  printf("\n");
  printf("+------+------------+------------+----------+\n");
  printf("|   %sDifftest FAILED at PC = " FMT_WORD "%s    |\n", ANSI_FG_YELLOW, pc, ANSI_NONE);
  printf("+------+------------+------------+----------+\n");
  printf("| Reg  | REF        | NPC        | Status   |\n");
  printf("+------+------------+------------+----------+\n");
  
  // 打印通用寄存器
  for (int i = 0; i < num_regs; i++) {
    print_reg_row(reg_name(i), ref_r->gpr[i], cpu.gpr[i]);
  }
  
  // 打印 PC
  printf("+------+------------+------------+----------+\n");
  print_reg_row("pc", ref_r->pc, cpu.pc);
  
  printf("+------+------------+------------+----------+\n");
  printf("\n");
}

bool isa_difftest_checkregs(const CPU_state *ref_r, vaddr_t pc) {
  bool all_match = true;
  int num_regs = MUXDEF(CONFIG_RVE, 16, 32);
  
  // 先检查是否有差异
  for (int i = 0; i < num_regs; i++) {
    if (ref_r->gpr[i] != cpu.gpr[i]) {
      all_match = false;
      break;
    }
  }
  
  if (ref_r->pc != cpu.pc) {
    all_match = false;
  }
  
  // 如果有差异，打印对比表
  if (!all_match) {
    print_diff_table(ref_r, pc);
  }
  
  return all_match;
}

void isa_difftest_attach() {}

