#ifndef NPC_TRACE_H_
#define NPC_TRACE_H_

#include <npc/ring_buffer.hh>
#include <npc/trace_config.hh>
#include <npc/common.hh>
#include <algorithm>
#include <format>
#include <span>

void npc_core_flush_trace();

namespace npc::trace {

inline constexpr int kInstMaxBytes = 4;
inline constexpr int kBytesPerHexGroup = 3;

// ======================== InstructionTrace ========================

struct ItraceItem {
  vaddr_t pc;
  vaddr_t snpc;
  uint32_t inst;
};

inline void gen_logbuf(std::span<char> logbuf, vaddr_t pc, vaddr_t snpc,
                       uint32_t inst) {
  const int ilen = static_cast<int>(snpc - pc);
  // NOLINTNEXTLINE(cppcoreguidelines-pro-type-reinterpret-cast) - type pun for instruction bytes
  const auto *bytes = reinterpret_cast<const uint8_t *>(&inst);

  auto *out = logbuf.data();
  size_t size = logbuf.size();
  auto it = std::format_to_n(out, size, "{:#010x}:", pc);
  out = it.out;
  size_t remaining = size - static_cast<size_t>(out - logbuf.data());

  for (int i = ilen - 1; i >= 0; i--) {
    it = std::format_to_n(out, remaining, " {:02x}", bytes[i]);
    out = it.out;
    remaining = size - static_cast<size_t>(out - logbuf.data());
  }

  int space_len = (kInstMaxBytes - ilen) * kBytesPerHexGroup + 1;
  if (space_len < 1) space_len = 1;
  out = std::fill_n(out, space_len, ' ');
  remaining = size - static_cast<size_t>(out - logbuf.data());
  disassemble(out, static_cast<int>(remaining), pc, const_cast<uint8_t *>(bytes),
             ilen);
}

// Backward-compatible overload for (char*, size_t) call sites.
inline void gen_logbuf(char *logbuf, size_t size, vaddr_t pc, vaddr_t snpc,
                       uint32_t inst) {
  gen_logbuf(std::span<char>(logbuf, size), pc, snpc, inst);
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
      Log("Last {} instructions:", ring_.capacity());
      char logbuf[128];
      for (auto it = ring_.begin(); it != ring_.end(); ++it) {
        gen_logbuf(logbuf, sizeof(logbuf), it->pc, it->snpc, it->inst);
        if (ring_.is_last(it.index()))
          _Log(ANSI_FMT("--> {}", ANSI_FG_BLUE) "\n", logbuf);
        else
          _Log(ANSI_FMT("    {}", ANSI_FG_BLUE) "\n", logbuf);
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
      Log("Last {} memory accesses:", ring_.capacity());
      for (const auto &item : ring_) {
        _Log(ANSI_FMT("    {} pc={:08x} addr={:08x} len={} data={:08x}",
                      ANSI_FG_BLUE) "\n",
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
      Log("Last {} exceptions/interrupts:", ring_.capacity());
      for (const auto &item : ring_) {
        if (item.type == 'R') {
          _Log(ANSI_FMT("    {} epc={:08x} (return from exception/interrupt)",
                        ANSI_FG_BLUE) "\n",
               item.type, item.epc);
        } else {
          _Log(ANSI_FMT("    {} cause={} ({}) epc={:08x} handler={:08x}",
                        ANSI_FG_BLUE) "\n",
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
