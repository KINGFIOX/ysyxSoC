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

#ifndef __FTRACE_H__
#define __FTRACE_H__

#include <common.h>

#ifdef CONFIG_FTRACE

void init_ftrace(const char *img_file);
void ftrace_call(vaddr_t pc, vaddr_t target);
void ftrace_ret(vaddr_t pc);
void ftrace_dump(void);

#else
// 只是用来骗过编译编译器的
static inline void init_ftrace(const char *elf_file) { (void)elf_file; }
static inline void ftrace_call(vaddr_t pc, vaddr_t target) {
  (void)pc;
  (void)target;
}
static inline void ftrace_ret(vaddr_t pc) { (void)pc; }
static inline void ftrace_dump(void) {}
#endif

#endif
