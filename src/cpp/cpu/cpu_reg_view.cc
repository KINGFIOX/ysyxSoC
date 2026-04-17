#include "cpu/cpu_reg_view.h"

#include "absl/status/statusor.h"
#include "absl/strings/str_format.h"
#include "absl/strings/string_view.h"

namespace npc {

absl::StatusOr<uint64_t> CpuRegView::value(absl::string_view name) const {
  if (name == "pc") {
    return pc();
  }

  struct CsrEntry {
    absl::string_view name;
    uint64_t (CpuRegView::*getter)() const;
  };
  static constexpr CsrEntry kCsrEntries[] = {
      {.name = "mstatus", .getter = &CpuRegView::mstatus},
      {.name = "mtvec", .getter = &CpuRegView::mtvec},
      {.name = "mepc", .getter = &CpuRegView::mepc},
      {.name = "mcause", .getter = &CpuRegView::mcause},
      {.name = "mtval", .getter = &CpuRegView::mtval},
      {.name = "medeleg", .getter = &CpuRegView::medeleg},
      {.name = "mideleg", .getter = &CpuRegView::mideleg},
      {.name = "mie", .getter = &CpuRegView::mie},
      {.name = "mscratch", .getter = &CpuRegView::mscratch},
      {.name = "menvcfg", .getter = &CpuRegView::menvcfg},
      {.name = "mcounteren", .getter = &CpuRegView::mcounteren},
      {.name = "pmpcfg0", .getter = &CpuRegView::pmpcfg0},
      {.name = "pmpaddr0", .getter = &CpuRegView::pmpaddr0},
      {.name = "stvec", .getter = &CpuRegView::stvec},
      {.name = "sepc", .getter = &CpuRegView::sepc},
      {.name = "scause", .getter = &CpuRegView::scause},
      {.name = "stval", .getter = &CpuRegView::stval},
      {.name = "sscratch", .getter = &CpuRegView::sscratch},
      {.name = "satp", .getter = &CpuRegView::satp},
      {.name = "stimecmp", .getter = &CpuRegView::stimecmp},
      {.name = "mvendorid", .getter = &CpuRegView::mvendorid},
      {.name = "marchid", .getter = &CpuRegView::marchid},
      {.name = "mhartid", .getter = &CpuRegView::mhartid},
  };
  for (const auto& e : kCsrEntries) {
    if (name == e.name) {
      return (this->*e.getter)();
    }
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
