#ifndef NPC_TRACER_FTRACE_H_
#define NPC_TRACER_FTRACE_H_

#include <cstdint>
#include <filesystem>
#include <ostream>
#include <string>

#include "absl/container/flat_hash_map.h"
#include "absl/strings/str_format.h"
#include "tracer/ring_buf.h"

namespace npc {

inline constexpr size_t kFtraceCapacity = 32;

enum class FuncType { kCall, kRet };

struct FTraceEntry {
  uint64_t pc = 0;
  uint64_t dnpc = 0;
  uint32_t depth = 0;
  FuncType func_type = FuncType::kCall;
  std::string func_name;
  std::string disasm;

  FTraceEntry() = default;
  FTraceEntry(uint64_t pc, uint64_t dnpc, uint32_t depth, FuncType func_type,
              std::string func_name, std::string disasm)
      : pc(pc),
        dnpc(dnpc),
        depth(depth),
        func_type(func_type),
        func_name(std::move(func_name)),
        disasm(std::move(disasm)) {}

  friend std::ostream& operator<<(std::ostream& os, const FTraceEntry& e) {
    std::string indent(e.depth * 2, ' ');
    if (e.func_type == FuncType::kCall) {
      os << absl::StreamFormat("0x%016x: %scall [%s@0x%016x] (%s)", e.pc,
                                indent, e.func_name, e.dnpc, e.disasm);
    } else {
      os << absl::StreamFormat("0x%016x: %sret (%s)", e.pc, indent, e.disasm);
    }
    return os;
  }
};

class FuncTracer {
 public:
  explicit FuncTracer(const std::filesystem::path& elf_path);

  void push_call(uint64_t pc, uint64_t dnpc, const std::string& disasm);
  void push_ret(uint64_t pc, uint64_t dnpc, const std::string& disasm);

  RingBuf<FTraceEntry> ring_buf;
  absl::flat_hash_map<uint64_t, std::string> symtab;

 private:
  uint32_t depth_ = 0;
};

}  // namespace npc

#endif  // NPC_TRACER_FTRACE_H_
