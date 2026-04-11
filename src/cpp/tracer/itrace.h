#ifndef NPC_TRACER_ITRACE_H_
#define NPC_TRACER_ITRACE_H_

#include <cstdint>
#include <ostream>
#include <string>

#include "absl/strings/str_format.h"

namespace npc {

struct ITraceEntry {
  uint64_t pc = 0;
  uint32_t inst = 0;
  std::string disasm;

  ITraceEntry() = default;
  ITraceEntry(uint64_t pc, uint32_t inst, std::string disasm)
      : pc(pc), inst(inst), disasm(std::move(disasm)) {}

  friend std::ostream& operator<<(std::ostream& os, const ITraceEntry& e) {
    return os << absl::StreamFormat("0x%016x: [%08x] %s", e.pc, e.inst,
                                     e.disasm);
  }
};

}  // namespace npc

#endif  // NPC_TRACER_ITRACE_H_
