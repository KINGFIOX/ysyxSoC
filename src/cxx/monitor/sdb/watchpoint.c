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

#include "sdb.h"
#include "utils.h"
#include <stdio.h>

#define NR_WP 32

typedef struct watchpoint {
  int NO;
  struct watchpoint *next;

  char expr[1024];   // 记录表达式
  word_t last_value; // 上一次的值

} WP;

static WP wp_pool[NR_WP] = {};
static WP *head = NULL, *free_ = NULL;

void init_wp_pool() {
  for (int i = 0; i < NR_WP; i++) {
    wp_pool[i].NO = i;
    wp_pool[i].next = (i == NR_WP - 1 ? NULL : &wp_pool[i + 1]);
  }

  head = NULL;
  free_ = wp_pool;
}

static WP *new_wp(const char *expr, word_t last_value) {
  Assert(free_ != NULL, "watchpoint pool is full");
  WP *wp = free_;
  free_ = free_->next; // pop from free_list

  wp->next = head; // push to watchpoint list
  head = wp;

  strncpy(wp->expr, expr, sizeof(wp->expr) - 1);
  wp->expr[sizeof(wp->expr) - 1] = '\0';
  wp->last_value = last_value;
  return wp;
}

static void free_wp(WP *wp) {
  wp->next = free_;
  free_ = wp;
}

int add_watchpoint(const char *expr) {
  bool success = true;
  word_t val = expr_eval(expr, &success);
  if (!success) {
    printf("expression evaluation failed, watchpoint not set: %s\n", expr);
    return -1;
  }

  WP *wp = new_wp(expr, val);
  printf("watchpoint %d: %s\ncurrent value = " FMT_WORD "\n", wp->NO, wp->expr,
         wp->last_value);
  return wp->NO;
}

bool delete_watchpoint(int no) {
  WP *prev = NULL;
  WP *cur = head;
  while (cur != NULL && cur->NO != no) { // find
    prev = cur;
    cur = cur->next;
  }

  if (cur == NULL) {
    printf("watchpoint %d not found\n", no);
    return false;
  }

  if (prev == NULL) {
    head = cur->next;
  } else {
    prev->next = cur->next;
  }
  free_wp(cur);
  printf("watchpoint %d deleted\n", no);
  return true;
}

void list_watchpoints(void) {
  if (head == NULL) {
    printf("no watchpoints\n");
    return;
  }

  printf("Num\tExpr\tValue\n");
  for (WP *cur = head; cur != NULL; cur = cur->next) {
    printf("%d\t%s\t" FMT_WORD "\n", cur->NO, cur->expr, cur->last_value);
  }
}

bool check_watchpoints(void) {
  bool triggered = false;

  for (WP *cur = head; cur != NULL; cur = cur->next) {
    bool success = false;
    word_t val = expr_eval(cur->expr, &success);
    if (!success) {
      printf("watchpoint %d expression evaluation failed: %s\n", cur->NO,
             cur->expr);
      continue;
    }

    if (val != cur->last_value) {
      printf("watchpoint %d triggered: %s\n", cur->NO, cur->expr);
      printf("old value = " FMT_WORD ", new value = " FMT_WORD "\n",
             cur->last_value, val);
      cur->last_value = val; // 可能同时触发多个, 因此这里不能 break.
                             // 需要更新完其他的watchpoint
      triggered = true;
    }
  }

  if (triggered) {
    if (npc_state.state == NPC_RUNNING) {
      npc_state.state = NPC_STOP;
    }
  }

  return triggered;
}
