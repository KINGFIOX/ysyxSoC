#include "cpu/abstract_cpu.h"

#include "absl/status/statusor.h"
#include "absl/strings/str_format.h"
#include "absl/strings/string_view.h"

namespace npc {

absl::StatusOr<uint64_t> AbstractCpu::value(absl::string_view name) const {
  if (name == "pc") {
    return pc();
  }

  if (name == "mstatus") {
    return mstatus();
  }
  if (name == "mtvec") {
    return mtvec();
  }
  if (name == "mepc") {
    return mepc();
  }
  if (name == "mcause") {
    return mcause();
  }
  if (name == "mtval") {
    return mtval();
  }
  if (name == "mvendorid") {
    return mvendorid();
  }
  if (name == "marchid") {
    return marchid();
  }

  // x0..x31
  if (name.size() >= 2 && name[0] == 'x') {
    int idx = 0;
    for (size_t i = 1; i < name.size(); ++i) {
      if (name[i] < '0' || name[i] > '9') {
        return absl::InvalidArgumentError(
            absl::StrFormat("invalid register name: %s", name));
      }
      idx = (idx * 10) + (name[i] - '0');
    }
    if (idx >= 0 && idx < 32) {
      if (idx == 0) {
        return uint64_t{0};
      }
      return gpr(idx);
    }
  }

  // ABI names
  struct AbiEntry {
    absl::string_view name;
    int index;
  };
  static constexpr AbiEntry kAbiNames[] = {
      {.name = "zero", .index = 0}, {.name = "ra", .index = 1},
      {.name = "sp", .index = 2},   {.name = "gp", .index = 3},
      {.name = "tp", .index = 4},   {.name = "t0", .index = 5},
      {.name = "t1", .index = 6},   {.name = "t2", .index = 7},
      {.name = "s0", .index = 8},   {.name = "fp", .index = 8},
      {.name = "s1", .index = 9},   {.name = "a0", .index = 10},
      {.name = "a1", .index = 11},  {.name = "a2", .index = 12},
      {.name = "a3", .index = 13},  {.name = "a4", .index = 14},
      {.name = "a5", .index = 15},  {.name = "a6", .index = 16},
      {.name = "a7", .index = 17},  {.name = "s2", .index = 18},
      {.name = "s3", .index = 19},  {.name = "s4", .index = 20},
      {.name = "s5", .index = 21},  {.name = "s6", .index = 22},
      {.name = "s7", .index = 23},  {.name = "s8", .index = 24},
      {.name = "s9", .index = 25},  {.name = "s10", .index = 26},
      {.name = "s11", .index = 27}, {.name = "t3", .index = 28},
      {.name = "t4", .index = 29},  {.name = "t5", .index = 30},
      {.name = "t6", .index = 31},
  };

  for (const auto& entry : kAbiNames) {
    if (name == entry.name) {
      if (entry.index == 0) {
        return uint64_t{0};
      }
      return gpr(entry.index);
    }
  }

  return absl::InvalidArgumentError(
      absl::StrFormat("invalid register name: %s", name));
}

}  // namespace npc
