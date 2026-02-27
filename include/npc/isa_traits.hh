#ifndef NPC_ISA_TRAITS_HH_
#define NPC_ISA_TRAITS_HH_

#include <array>
#include <cinttypes>
#include <cstdint>
#include <cstdio>
#include <generated/autoconf.h>
#include <string_view>

namespace npc {

// RV32I type aliases and constants
using word_t = uint32_t;
using sword_t = int32_t;
static constexpr unsigned xlen = 32;
static constexpr bool is_64bit = false;
static constexpr const char *fmt_word = "0x%08" PRIx32;

static constexpr int num_gprs = 32;

// RISC-V register names
inline constexpr std::array<std::string_view, 32> gpr_names = {
    "$0", "ra", "sp", "gp", "tp",  "t0",  "t1", "t2", "s0", "s1", "a0",
    "a1", "a2", "a3", "a4", "a5",  "a6",  "a7", "s2", "s3", "s4", "s5",
    "s6", "s7", "s8", "s9", "s10", "s11", "t3", "t4", "t5", "t6",
};

// Standard M-mode CSR indices
enum class Csr : uint16_t {
  Mstatus = 0x0300,
  Mtvec = 0x0305,
  Mepc = 0x0341,
  Mcause = 0x0342,
  Mtval = 0x0343,
  Mvendorid = 0x0F11,
  Marchid = 0x0F12,
};

constexpr std::string_view csr_name(Csr c) {
  switch (c) {
  case Csr::Mstatus:
    return "mstatus";
  case Csr::Mtvec:
    return "mtvec";
  case Csr::Mepc:
    return "mepc";
  case Csr::Mcause:
    return "mcause";
  case Csr::Mtval:
    return "mtval";
  case Csr::Mvendorid:
    return "mvendorid";
  case Csr::Marchid:
    return "marchid";
  }
  return "unknown";
}

struct CpuState {
  word_t gpr[num_gprs]{};
  word_t pc{};
  word_t csr[0x1000]{};

  word_t read_csr(Csr idx) const { return csr[static_cast<uint16_t>(idx)]; }
  void write_csr(Csr idx, word_t val) { csr[static_cast<uint16_t>(idx)] = val; }

  void display() const {
    for (int i = 0; i < num_gprs; i++)
      std::printf("%-4.*s\t"
                  "0x%08x"
                  "\n",
                  static_cast<int>(gpr_names[i].size()), gpr_names[i].data(),
                  gpr[i]);
    std::printf("pc\t"
                "0x%08x"
                "\n",
                pc);
  }
};

class Cpu {
  CpuState state_{};

public:
  void init();  // Implemented in init.cc
  void reset(); // Implemented in init.cc

  [[nodiscard]] word_t pc() const { return state_.pc; }
  void set_pc(word_t v) { state_.pc = v; }

  [[nodiscard]] word_t gpr(int i) const { return state_.gpr[i]; }
  void set_gpr(int i, word_t v) { state_.gpr[i] = v; }

  [[nodiscard]] word_t read_csr(Csr idx) const {
    return state_.read_csr(idx);
  }
  void write_csr(Csr idx, word_t v) { state_.write_csr(idx, v); }

  [[nodiscard]] word_t read_csr(int idx) const { return state_.csr[idx]; }
  void write_csr(int idx, word_t v) { state_.csr[idx] = v; }

  void display() const { state_.display(); }

  [[nodiscard]] CpuState *state_ptr() { return &state_; }
  [[nodiscard]] const CpuState *state_ptr() const { return &state_; }
};

inline Cpu &cpu() {
  static Cpu instance;
  return instance;
}

} // namespace npc

#endif // NPC_ISA_TRAITS_HH_
