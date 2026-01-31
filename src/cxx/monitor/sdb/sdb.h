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

#ifndef __SDB_H__
#define __SDB_H__

#include <common.h>

word_t expr_eval(const char *expr, bool *success);

#ifdef CONFIG_WATCHPOINT
void init_wp_pool(void);
int add_watchpoint(const char *expr);
bool delete_watchpoint(int no);
void list_watchpoints(void);
bool check_watchpoints(void);
#else
static inline void init_wp_pool(void) {}
static inline int add_watchpoint(const char *expr) { return 0; }
static inline bool delete_watchpoint(int no) { return true; }
static inline void list_watchpoints(void) {}
static inline bool check_watchpoints(void) { return false; }
#endif

extern const char *parse_error_msg;

#endif
