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

#include "../monitor/sdb/sdb.h"
#include "debug.h"
#include "isa.h"
#include <cpu/core.h>
#include <cpu/cpu.h>
#include <cpu/decode.h>
#include <cpu/difftest.h>
#include <device/map.h>
#include <memory/vaddr.h>
#include <ftrace.h>
#include "../isa/riscv32/local-include/reg.h" // etrace

#ifdef CONFIG_ITRACE
#include <utils/ringbuf.h>
#endif

/* The assembly code of instructions executed is only output to the screen
 * when the number of instructions executed is less than this value.
 * This is useful when you use the `si' command.
 * You can modify this value as you want.
 */
#define MAX_INST_TO_PRINT 10

CPU_state cpu = {};
uint64_t g_nr_guest_inst = 0;
static uint64_t g_timer = 0; // unit: us
static bool g_print_step = false;

void device_update();

static void trace_and_difftest(Decode *_this, vaddr_t dnpc) {
#ifdef CONFIG_ITRACE_COND
  if (ITRACE_COND) {
    log_write("%s\n", _this->logbuf);
  }
#endif
  if (g_print_step) {
    IFDEF(CONFIG_ITRACE, puts(_this->logbuf));
  }

#ifdef CONFIG_DIFFTEST
  difftest_step(_this->pc, dnpc);
#endif

#ifdef CONFIG_WATCHPOINT
  check_watchpoints();
#endif
}

#ifdef CONFIG_ITRACE
void gen_logbuf(char *logbuf, size_t size, vaddr_t pc, vaddr_t snpc, const ISADecodeInfo *isa) {
  // 效果:
  // 0x80000000: 00 00 02 97 auipc   t0, 0
  char *p = logbuf;
  p += snprintf(p, size, FMT_WORD ":", pc);
  int ilen = snpc - pc;
  uint8_t *inst = (uint8_t *)&isa->inst;
  for (int i = ilen - 1; i >= 0; i--) {
    p += snprintf(p, 4, " %02x", inst[i]);
  }
  int ilen_max = 4;
  int space_len = ilen_max - ilen;
  if (space_len < 0)
    space_len = 0;
  space_len = space_len * 3 + 1;
  memset(p, ' ', space_len); // 打印一些空格, 用来对齐的
  p += space_len;

  bool ret = disassemble(p, size - (p - logbuf), pc, (uint8_t *)&isa->inst, ilen);
  if (!ret) {
    invalid_inst(pc);
  }
}
#endif

#ifdef CONFIG_ITRACE

typedef struct {
  vaddr_t pc;
  vaddr_t snpc;
  ISADecodeInfo isa;
} ItraceItem;

static RINGBUF_DEFINE(ItraceItem, CONFIG_IRINGBUF_SIZE) g_iringbuf = RINGBUF_INIT;

#define LogInst(format, ...)                                                   \
  _Log(ANSI_FMT(format, ANSI_FG_BLUE) "\n", ##__VA_ARGS__)

static void iringbuf_push(vaddr_t pc, vaddr_t snpc, const ISADecodeInfo *isa) {
  RINGBUF_PUSH(g_iringbuf, CONFIG_IRINGBUF_SIZE,
      ((ItraceItem){.pc = pc, .snpc = snpc, .isa = *isa}));
}

static void dump_iringbuf(void) {
  if (RINGBUF_EMPTY(g_iringbuf))
    return;

  Log("Last %d instructions:", CONFIG_IRINGBUF_SIZE);
  char logbuf[128];
  RINGBUF_FOREACH(g_iringbuf, CONFIG_IRINGBUF_SIZE, idx, pos) {
    const ItraceItem *it = RINGBUF_GET(g_iringbuf, pos);
    gen_logbuf(logbuf, sizeof(logbuf), it->pc, it->snpc, &it->isa);

    if (RINGBUF_IS_LAST(g_iringbuf, idx)) {
      LogInst("--> %s", logbuf);
    } else {
      LogInst("    %s", logbuf);
    }
  }
}

#endif

static bool exec_once(Decode *s) { return npc_core_step(s); }

// ===============================  decode  ===============================

enum {
  TYPE_I, TYPE_U, TYPE_S, TYPE_B, TYPE_J, TYPE_R,
  TYPE_N, // none
};

#define immI() do { *imm = SEXT(BITS(i, 31, 20), 12); } while(0)
#define immU() do { *imm = SEXT(BITS(i, 31, 12), 20) << 12; } while(0)
#define immS() do { *imm = (SEXT(BITS(i, 31, 25), 7) << 5) | BITS(i, 11, 7); } while(0)
#define immB() do { *imm = SEXT((BITS(i, 31, 31) << 12) | (BITS(i, 7, 7) << 11) | (BITS(i, 30, 25) << 5)  | (BITS(i, 11, 8) << 1), 13); } while(0)
#define immJ() do { *imm = SEXT((BITS(i, 31, 31) << 20) | (BITS(i, 19, 12) << 12) | (BITS(i, 20, 20) << 11) | (BITS(i, 30, 21) << 1), 21); } while(0)

#if defined(CONFIG_FTRACE) || defined(CONFIG_ETRACE)

static void decode_operand(const Decode *s, int *rd, word_t *src1, word_t *src2, word_t *imm, int type) {
  uint32_t i = s->isa.inst;
  *rd     = BITS(i, 11, 7);
  switch (type) {
    case TYPE_I: immI(); break;
    case TYPE_U: immU(); break;
    case TYPE_S: immS(); break;
    case TYPE_B: immB(); break;
    case TYPE_J: immJ(); break;
    case TYPE_R:         break;
    case TYPE_N:         break;
    default: panic("unsupported type = %d", type);
  }
}

#endif

#define INSTPAT_INST(s) ((s)->isa.inst)
#define INSTPAT_MATCH(s, name, type, ... /* execute body */ ) { \
  int rd = 0; \
  word_t src1 = 0, src2 = 0, imm = 0; \
  decode_operand(s, &rd, &src1, &src2, &imm, concat(TYPE_, type)); \
  __VA_ARGS__ ; \
}

// ===============================  ftrace  ===============================

#ifdef CONFIG_FTRACE

static void ftrace_log(const Decode *s) {
  INSTPAT_START();
  INSTPAT("??????? ????? ????? ????? ????? 11011 11", jal   , J, {
    if (rd == 1) {
      ftrace_call(s->pc, s->dnpc);
    }
  });
  INSTPAT("??????? ????? ????? 000 ????? 11001 11", jalr   , I, {
    int rs1 = BITS(s->isa.inst, 19, 15);
    if (rd == 0 && rs1 == 1 && imm == 0) { // `ret` aka. `jalr zero, 0(ra)`
      ftrace_ret(s->pc); // 函数返回
    } else if (rd != 0) {
      ftrace_call(s->pc, s->dnpc); // 函数指针调用
    }
  });
  INSTPAT_END();
}

#endif // CONFIG_FTRACE

// ===============================  etrace  ===============================

#ifdef CONFIG_ETRACE

void etrace_push(char type, word_t cause, vaddr_t epc, vaddr_t handler);

static void etrace_log(const Decode *s) {
  INSTPAT_START();
  INSTPAT("0000000 00000 00000 000 00000 11100 11", ecall  , I, {
    etrace_push('E', 11, cpu.csr[MEPC], cpu.csr[MTVEC]);
  });
  INSTPAT("0011000 00010 00000 000 00000 11100 11", mret   , R, {
    etrace_push('R', csr(MCAUSE), cpu.csr[MEPC], 0);
  });
  INSTPAT_END();
}

#endif // CONFIG_ETRACE

static void execute(uint64_t n) {
  Decode s;

  for (; n > 0; n--) {
    if (!exec_once(&s)) {
      set_npc_state(NPC_ABORT, cpu.pc, -1);
      break;
    }

#ifdef CONFIG_ITRACE
    // 生成日志(完整)
    gen_logbuf(s.logbuf, sizeof(s.logbuf), s.pc, s.snpc, &s.isa);
    // 最近的 CONFIG_IRINGBUF_SIZE 条指令
    iringbuf_push(s.pc, s.snpc, &s.isa);
#endif

#ifdef CONFIG_FTRACE
  ftrace_log(&s);
#endif

#ifdef CONFIG_ETRACE
  etrace_log(&s);
#endif
    g_nr_guest_inst++;
    trace_and_difftest(&s, cpu.pc);
    if (npc_state.state != NPC_RUNNING) break;
    IFDEF(CONFIG_DEVICE, device_update());
  }
}

static void statistic() {
#define NUMBERIC_FMT "%" PRIu64
  Log("host time spent = " NUMBERIC_FMT " us", g_timer);
  Log("total guest instructions = " NUMBERIC_FMT, g_nr_guest_inst);
  if (g_timer > 0)
    Log("simulation frequency = " NUMBERIC_FMT " inst/s",
        g_nr_guest_inst * 1000000 / g_timer);
  else
    Log("Finish running in less than 1 us and can not calculate the simulation "
        "frequency");
}

static void dump_trace_msg(void) {
#ifdef CONFIG_ITRACE
  dump_iringbuf();
#endif
#ifdef CONFIG_MTRACE
  mtrace_dump();
#endif
#ifdef CONFIG_DTRACE
  dtrace_dump();
#endif
#ifdef CONFIG_ETRACE
  etrace_dump();
#endif
#ifdef CONFIG_FTRACE
  ftrace_dump();
#endif
#ifdef CONFIG_VERILATOR_TRACE
  npc_core_flush_trace();
#endif
}

void assert_fail_msg() {
  isa_reg_display();
  dump_trace_msg();
  statistic();
}

/* Simulate how the CPU works. */
void cpu_exec(uint64_t n) {
  g_print_step = (n < MAX_INST_TO_PRINT);
  switch (npc_state.state) {
  case NPC_END:
  case NPC_ABORT:
  case NPC_QUIT:
    printf("Program execution has ended. To restart the program, exit NPC and "
           "run again.\n");
    return;
  default:
    npc_state.state = NPC_RUNNING;
  }

  uint64_t timer_start = get_time();

  execute(n);

  uint64_t timer_end = get_time();
  g_timer += timer_end - timer_start;

  switch (npc_state.state) {
  case NPC_RUNNING:
    npc_state.state = NPC_STOP;
    break; // 重新回到 sdb 的 mainloop 中, 等待用户的命令

  // 下面几个状态, 运行结束
  case NPC_ABORT: // host 内部错误
  case NPC_END:   // guest 程序执行完成
    Log("npc: %s at pc = " FMT_WORD,
        (npc_state.state == NPC_ABORT
             ? ANSI_FMT("ABORT", ANSI_FG_RED)
             : (npc_state.halt_ret == 0
                    ? ANSI_FMT("HIT GOOD TRAP", ANSI_FG_GREEN)
                    : ANSI_FMT("HIT BAD TRAP", ANSI_FG_RED))),
        npc_state.halt_pc);
    // fall through
    dump_trace_msg();
  case NPC_QUIT:
    statistic();
  }
}
