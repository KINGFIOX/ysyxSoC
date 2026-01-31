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

#include <isa.h>
#include <memory/paddr.h>
#ifdef CONFIG_MTRACE
#include <cpu/cpu.h>
#include <utils/ringbuf.h>
#endif

#ifdef CONFIG_MTRACE
#define MTRACE_BUF_SIZE 16

#define LogMem(format, ...)                                                    \
  _Log(ANSI_FMT(format, ANSI_FG_BLUE) "\n", ##__VA_ARGS__)

typedef struct {
  vaddr_t addr;
  int len;
  word_t data;
  char type; // 'I' fetch, 'R' read, 'W' write
  word_t pc;
} MtraceItem;

static RINGBUF_DEFINE(MtraceItem, MTRACE_BUF_SIZE) mtrace_buf = RINGBUF_INIT;

static void mtrace_push(char type, vaddr_t addr, int len, word_t data,
                        word_t pc) {
  RINGBUF_PUSH(mtrace_buf, MTRACE_BUF_SIZE,
      ((MtraceItem){.addr = addr, .len = len, .data = data, .type = type, .pc = pc}));
}

void mtrace_dump(void) {
  if (RINGBUF_EMPTY(mtrace_buf)) {
    return;
  }

  LogMem("Last %d memory accesses:", MTRACE_BUF_SIZE);
  RINGBUF_FOREACH(mtrace_buf, MTRACE_BUF_SIZE, idx, pos) {
    const MtraceItem *it = RINGBUF_GET(mtrace_buf, pos);
    LogMem("    %c pc=" FMT_WORD " addr=" FMT_WORD " len=%d data=" FMT_WORD,
           it->type, it->pc, it->addr, it->len, it->data);
  }
}
#endif

word_t vaddr_ifetch(vaddr_t addr, int len) { return paddr_read(addr, len); }

word_t vaddr_read(vaddr_t addr, int len) {
  word_t data = paddr_read(addr, len);
#ifdef CONFIG_MTRACE
  if (CONFIG_MTRACE_COND) {
    mtrace_push('R', addr, len, data, cpu.pc);
  }
#endif
  return data;
}

void vaddr_write(vaddr_t addr, int len, word_t data) {
#ifdef CONFIG_MTRACE
  if (CONFIG_MTRACE_COND) {
    mtrace_push('W', addr, len, data, cpu.pc);
  }
#endif
  paddr_write(addr, len, data);
}
