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
static constexpr int num_csrs = 0x1000;

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

// #derived[Debug]
struct CpuState {
  std::array<word_t, num_gprs> gpr;
  word_t pc;
  word_t mstatus;
  word_t mtvec;
  word_t mepc;
  word_t mcause;
  word_t mtval;
  word_t mvendorid;
  word_t marchid;
};

} // namespace npc

#endif // NPC_ISA_TRAITS_HH_
