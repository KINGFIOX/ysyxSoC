#ifndef NPC_TRACER_DTRACE_H_
#define NPC_TRACER_DTRACE_H_

#include <cstdint>
#include <ostream>
#include <string>

#include "absl/strings/str_format.h"

namespace npc {

enum class MemDir { kRead, kWrite };

struct DTraceEntry {
  uint64_t pc = 0;
  MemDir dir = MemDir::kRead;
  uint64_t addr = 0;
  uint64_t data = 0;
  uint8_t width = 0;
  std::string disasm;

  DTraceEntry() = default;
  DTraceEntry(uint64_t pc, MemDir dir, uint64_t addr, uint64_t data,
              uint8_t width, std::string disasm)
      : pc(pc),
        dir(dir),
        addr(addr),
        data(data),
        width(width),
        disasm(std::move(disasm)) {}

  friend std::ostream& operator<<(std::ostream& os, const DTraceEntry& e) {
    const char* dir_str = (e.dir == MemDir::kRead) ? "R" : "W";
    return os << absl::StreamFormat(
               "0x%016x: %s [0x%016x] w%d = 0x%016x  %s", e.pc, dir_str,
               e.addr, e.width, e.data, e.disasm);
  }
};

}  // namespace npc

#endif  // NPC_TRACER_DTRACE_H_
