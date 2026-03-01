#include <npc/trace.hh>
#include <npc/state.hh>
#include <npc/cpu.hh>
#include <npc/cpu_model.hh>
#include <npc/difftest.hh>
#include <npc/isa.hh>
#include "../monitor/sdb/sdb.hh"

using Cfg = npc::trace::Config;

#define MAX_INST_TO_PRINT 10

uint64_t g_nr_guest_inst = 0;
npc::trace::TraceManager<> g_trace;

static uint64_t g_timer = 0;
static bool g_print_step = false;

#ifdef CONFIG_DIFFTEST
static npc::Scoreboard g_scoreboard;
#endif

static void trace_and_difftest(const npc::StepResult &r) {
  word_t snpc = r.pc + 4;

  if constexpr (Cfg::itrace) {
    if (ITRACE_COND) {
      char logbuf[128];
      npc::trace::gen_logbuf(logbuf, sizeof(logbuf), r.pc, snpc, r.inst);
      _Log("{}\n", logbuf);
    }
  }

  if (g_print_step) {
    if constexpr (Cfg::itrace) {
      char logbuf[128];
      npc::trace::gen_logbuf(logbuf, sizeof(logbuf), r.pc, snpc, r.inst);
      puts(logbuf);
    }
  }

  if constexpr (Cfg::difftest) {
    g_scoreboard.check(npc::dut(), npc::ref(), r);
  }

  if constexpr (Cfg::watchpoint) {
    check_watchpoints();
  }
}

// ===============================  decode  ===============================

enum {
  TYPE_I, TYPE_U, TYPE_S, TYPE_B, TYPE_J, TYPE_R,
  TYPE_N,
};

#define immI() do { *imm = SEXT(BITS(i, 31, 20), 12); } while(0)
#define immU() do { *imm = SEXT(BITS(i, 31, 12), 20) << 12; } while(0)
#define immS() do { *imm = (SEXT(BITS(i, 31, 25), 7) << 5) | BITS(i, 11, 7); } while(0)
#define immB() do { *imm = SEXT((BITS(i, 31, 31) << 12) | (BITS(i, 7, 7) << 11) | (BITS(i, 30, 25) << 5)  | (BITS(i, 11, 8) << 1), 13); } while(0)
#define immJ() do { *imm = SEXT((BITS(i, 31, 31) << 20) | (BITS(i, 19, 12) << 12) | (BITS(i, 20, 20) << 11) | (BITS(i, 30, 21) << 1), 21); } while(0)

#if defined(CONFIG_FTRACE) || defined(CONFIG_ETRACE)

static void decode_operand(const npc::StepResult *s, int *rd, word_t *src1,
                           word_t *src2, word_t *imm, int type) {
  (void)src1;
  (void)src2;
  uint32_t i = s->inst;
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

#define INSTPAT_INST(s) ((s)->inst)
#define INSTPAT_MATCH(s, name, type, ... /* execute body */ ) { \
  int rd = 0; \
  word_t src1 = 0, src2 = 0, imm = 0; \
  decode_operand(s, &rd, &src1, &src2, &imm, concat(TYPE_, type)); \
  __VA_ARGS__ ; \
}

// ===============================  ftrace  ===============================

#ifdef CONFIG_FTRACE

static void ftrace_log(const npc::StepResult *s) {
  INSTPAT_START();
  INSTPAT("??????? ????? ????? ????? ????? 11011 11", jal   , J, {
    if (rd == 1) {
      ftrace_call(s->pc, s->dnpc);
    }
  });
  INSTPAT("??????? ????? ????? 000 ????? 11001 11", jalr   , I, {
    int rs1 = BITS(s->inst, 19, 15);
    if (rd == 0 && rs1 == 1 && imm == 0) {
      ftrace_ret(s->pc);
    } else if (rd != 0) {
      ftrace_call(s->pc, s->dnpc);
    }
  });
  INSTPAT_END();
}

#endif // CONFIG_FTRACE

// ===============================  etrace  ===============================

#ifdef CONFIG_ETRACE

static void etrace_log(const npc::StepResult *s) {
  auto &d = npc::dut();
  INSTPAT_START();
  INSTPAT("0000000 00000 00000 000 00000 11100 11", ecall  , I, {
    g_trace.etrace().push('E', 11, d.mepc(), d.mtvec());
  });
  INSTPAT("0011000 00010 00000 000 00000 11100 11", mret   , R, {
    g_trace.etrace().push('R', d.mcause(), d.mepc(), 0);
  });
  INSTPAT_END();
}

#endif // CONFIG_ETRACE

static void execute(uint64_t n) {
  for (; n > 0; n--) {
    auto result = npc::dut().step();

    if (npc_state.state != NPC_RUNNING) break;

    if constexpr (Cfg::itrace) {
      g_trace.itrace().record(result.pc, result.pc + 4, result.inst);
    }

#ifdef CONFIG_FTRACE
    ftrace_log(&result);
#endif

#ifdef CONFIG_ETRACE
    etrace_log(&result);
#endif

    g_nr_guest_inst++;
    trace_and_difftest(result);
    if (npc_state.state != NPC_RUNNING) break;
  }
}

static void statistic() {
  Log("host time spent = {} us", g_timer);
  Log("total guest instructions = {}", g_nr_guest_inst);
  if (g_timer > 0)
    Log("simulation frequency = {} inst/s",
        g_nr_guest_inst * 1000000 / g_timer);
  else
    Log("Finish running in less than 1 us and can not calculate the simulation "
        "frequency");
}

void assert_fail_msg() {
  isa_reg_display();
  g_trace.dump_all();
  statistic();
}

void cpu_exec(uint64_t n) {
  g_print_step = (n < MAX_INST_TO_PRINT);
  switch (npc_state.state) {
  case NPC_END:
  case NPC_ABORT:
  case NPC_QUIT:
    std::printf("Program execution has ended. To restart the program, exit NPC and "
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
    break;

  case NPC_ABORT:
  case NPC_END:
    Log("npc: {} at pc = {:08x}",
        (npc_state.state == NPC_ABORT
             ? ANSI_FMT("ABORT", ANSI_FG_RED)
             : (npc_state.halt_ret == 0
                    ? ANSI_FMT("HIT GOOD TRAP", ANSI_FG_GREEN)
                    : ANSI_FMT("HIT BAD TRAP", ANSI_FG_RED))),
        npc_state.halt_pc);
    g_trace.dump_all();
    [[fallthrough]];
  case NPC_QUIT:
    statistic();
  }
}
