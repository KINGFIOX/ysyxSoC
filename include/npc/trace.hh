#ifndef NPC_TRACE_H_
#define NPC_TRACE_H_

#include <npc/ring_buffer.hh>
#include <npc/trace_config.hh>
#include <npc/common.hh>
#include <cstdio>
#include <cstring>

void npc_core_flush_trace();

namespace npc::trace {

// ======================== InstructionTrace ========================

struct ItraceItem {
  vaddr_t pc;
  vaddr_t snpc;
  uint32_t inst;
};

inline void gen_logbuf(char *logbuf, size_t size, vaddr_t pc, vaddr_t snpc,
                       uint32_t inst) {
  char *p = logbuf;
  p += snprintf(p, size, FMT_WORD ":", pc);
  int ilen = snpc - pc;
  auto *bytes = reinterpret_cast<uint8_t *>(&inst);
  for (int i = ilen - 1; i >= 0; i--)
    p += snprintf(p, 4, " %02x", bytes[i]);
  int space_len = (4 - ilen) * 3 + 1;
  if (space_len < 1) space_len = 1;
  std::memset(p, ' ', space_len);
  p += space_len;
  disassemble(p, static_cast<int>(size - (p - logbuf)), pc, bytes, ilen);
}

template <typename Cfg = Config>
class InstructionTrace {
  RingBuffer<ItraceItem, Cfg::iringbuf_size> ring_;

public:
  void record(vaddr_t pc, vaddr_t snpc, uint32_t inst) {
    if constexpr (Cfg::itrace) {
      ring_.push({pc, snpc, inst});
    }
  }

  void dump() const {
    if constexpr (Cfg::itrace) {
      if (ring_.empty()) return;
      Log("Last %zu instructions:", ring_.capacity());
      char logbuf[128];
      for (auto it = ring_.begin(); it != ring_.end(); ++it) {
        gen_logbuf(logbuf, sizeof(logbuf), it->pc, it->snpc, it->inst);
        if (ring_.is_last(it.index()))
          _Log(ANSI_FMT("--> %s", ANSI_FG_BLUE) "\n", logbuf);
        else
          _Log(ANSI_FMT("    %s", ANSI_FG_BLUE) "\n", logbuf);
      }
    }
  }
};

// ======================== MemoryTrace ========================

struct MtraceItem {
  vaddr_t addr;
  int len;
  word_t data;
  char type; // 'R' read, 'W' write
  word_t pc;
};

template <typename Cfg = Config>
class MemoryTrace {
  RingBuffer<MtraceItem, Cfg::mtrace_buf_size> ring_;

public:
  void on_read(vaddr_t addr, int len, word_t data, word_t pc) {
    if constexpr (Cfg::mtrace) {
      ring_.push({addr, len, data, 'R', pc});
    }
  }

  void on_write(vaddr_t addr, int len, word_t data, word_t pc) {
    if constexpr (Cfg::mtrace) {
      ring_.push({addr, len, data, 'W', pc});
    }
  }

  void dump() const {
    if constexpr (Cfg::mtrace) {
      if (ring_.empty()) return;
      Log("Last %zu memory accesses:", ring_.capacity());
      for (const auto &item : ring_) {
        _Log(ANSI_FMT("    %c pc=" FMT_WORD " addr=" FMT_WORD
                       " len=%d data=" FMT_WORD, ANSI_FG_BLUE) "\n",
             item.type, item.pc, item.addr, item.len, item.data);
      }
    }
  }
};

// ======================== ExceptionTrace ========================

struct EtraceItem {
  word_t cause;
  vaddr_t epc;
  vaddr_t handler;
  char type; // 'E' exception, 'I' interrupt, 'R' return
};

inline const char *get_exception_name(word_t cause) {
  switch (cause) {
  case 0:  return "instruction_address_misaligned";
  case 1:  return "instruction_access_fault";
  case 2:  return "illegal_instruction";
  case 3:  return "breakpoint";
  case 4:  return "load_address_misaligned";
  case 5:  return "load_access_fault";
  case 6:  return "store_address_misaligned";
  case 7:  return "store_access_fault";
  case 8:  return "user_ecall";
  case 9:  return "supervisor_ecall";
  case 10: return "virtual_supervisor_ecall";
  case 11: return "machine_ecall";
  case 12: return "instruction_page_fault";
  case 13: return "load_page_fault";
  case 15: return "store_page_fault";
  default: return "unknown";
  }
}

template <typename Cfg = Config>
class ExceptionTrace {
  RingBuffer<EtraceItem, Cfg::etrace_buf_size> ring_;

public:
  void push(char type, word_t cause, vaddr_t epc, vaddr_t handler) {
    if constexpr (Cfg::etrace) {
      ring_.push({cause, epc, handler, type});
    }
  }

  void dump() const {
    if constexpr (Cfg::etrace) {
      if (ring_.empty()) return;
      Log("Last %zu exceptions/interrupts:", ring_.capacity());
      for (const auto &item : ring_) {
        if (item.type == 'R') {
          _Log(ANSI_FMT("    %c epc=" FMT_WORD
                         " (return from exception/interrupt)",
                         ANSI_FG_BLUE) "\n",
               item.type, item.epc);
        } else {
          _Log(ANSI_FMT("    %c cause=%u (%s) epc=" FMT_WORD
                         " handler=" FMT_WORD, ANSI_FG_BLUE) "\n",
               item.type, static_cast<unsigned>(item.cause),
               get_exception_name(item.cause), item.epc, item.handler);
        }
      }
    }
  }
};

// ======================== TraceManager ========================

template <typename Cfg = Config>
class TraceManager {
  InstructionTrace<Cfg> itrace_;
  MemoryTrace<Cfg> mtrace_;
  ExceptionTrace<Cfg> etrace_;

public:
  auto &itrace() { return itrace_; }
  auto &mtrace() { return mtrace_; }
  auto &etrace() { return etrace_; }

  const auto &itrace() const { return itrace_; }
  const auto &mtrace() const { return mtrace_; }
  const auto &etrace() const { return etrace_; }

  void dump_all() {
    itrace_.dump();
    mtrace_.dump();
    etrace_.dump();
    if constexpr (Cfg::ftrace) {
      ::ftrace_dump();
    }
    if constexpr (Cfg::verilator_trace) {
      ::npc_core_flush_trace();
    }
  }
};

} // namespace npc::trace

extern npc::trace::TraceManager<> g_trace;

#endif // NPC_TRACE_H_
