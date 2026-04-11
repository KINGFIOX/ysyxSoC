#include "cpu/abstract_cpu.h"

#include "absl/status/statusor.h"
#include "absl/strings/str_format.h"
#include "absl/strings/string_view.h"

namespace npc {

absl::StatusOr<uint64_t> AbstractCpu::value(absl::string_view name) const {
  if (name == "pc") return pc();

  if (name == "mstatus") return mstatus();
  if (name == "mtvec") return mtvec();
  if (name == "mepc") return mepc();
  if (name == "mcause") return mcause();
  if (name == "mtval") return mtval();
  if (name == "mvendorid") return mvendorid();
  if (name == "marchid") return marchid();

  // x0..x31
  if (name.size() >= 2 && name[0] == 'x') {
    int idx = 0;
    for (size_t i = 1; i < name.size(); ++i) {
      if (name[i] < '0' || name[i] > '9')
        return absl::InvalidArgumentError(
            absl::StrFormat("invalid register name: %s", name));
      idx = idx * 10 + (name[i] - '0');
    }
    if (idx >= 0 && idx < 32) {
      if (idx == 0) return uint64_t{0};
      return gpr(idx);
    }
  }

  // ABI names
  struct AbiEntry {
    absl::string_view name;
    int index;
  };
  static constexpr AbiEntry kAbiNames[] = {
      {"zero", 0},  {"ra", 1},   {"sp", 2},   {"gp", 3},   {"tp", 4},
      {"t0", 5},    {"t1", 6},   {"t2", 7},   {"s0", 8},   {"fp", 8},
      {"s1", 9},    {"a0", 10},  {"a1", 11},  {"a2", 12},  {"a3", 13},
      {"a4", 14},   {"a5", 15},  {"a6", 16},  {"a7", 17},  {"s2", 18},
      {"s3", 19},   {"s4", 20},  {"s5", 21},  {"s6", 22},  {"s7", 23},
      {"s8", 24},   {"s9", 25},  {"s10", 26}, {"s11", 27}, {"t3", 28},
      {"t4", 29},   {"t5", 30},  {"t6", 31},
  };

  for (const auto& entry : kAbiNames) {
    if (name == entry.name) {
      if (entry.index == 0) return uint64_t{0};
      return gpr(entry.index);
    }
  }

  return absl::InvalidArgumentError(
      absl::StrFormat("invalid register name: %s", name));
}

}  // namespace npc
