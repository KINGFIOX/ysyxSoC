#ifndef NPC_CPU_CPU_REG_VIEW_H_
#define NPC_CPU_CPU_REG_VIEW_H_

#include <cstdint>

#include "absl/status/statusor.h"
#include "absl/strings/string_view.h"

namespace npc {

// M-mode trap setup / handling
inline constexpr uint16_t kCsrMstatus    = 0x0300;
inline constexpr uint16_t kCsrMedeleg    = 0x0302;
inline constexpr uint16_t kCsrMideleg    = 0x0303;
inline constexpr uint16_t kCsrMie        = 0x0304;
inline constexpr uint16_t kCsrMtvec      = 0x0305;
inline constexpr uint16_t kCsrMcounteren = 0x0306;
inline constexpr uint16_t kCsrMenvcfg    = 0x030A;
inline constexpr uint16_t kCsrMscratch   = 0x0340;
inline constexpr uint16_t kCsrMepc       = 0x0341;
inline constexpr uint16_t kCsrMcause     = 0x0342;
inline constexpr uint16_t kCsrMtval      = 0x0343;
inline constexpr uint16_t kCsrPmpcfg0    = 0x03A0;
inline constexpr uint16_t kCsrPmpaddr0   = 0x03B0;

// S-mode trap setup / handling
inline constexpr uint16_t kCsrStvec    = 0x0105;
inline constexpr uint16_t kCsrSscratch = 0x0140;
inline constexpr uint16_t kCsrSepc     = 0x0141;
inline constexpr uint16_t kCsrScause   = 0x0142;
inline constexpr uint16_t kCsrStval    = 0x0143;
inline constexpr uint16_t kCsrStimecmp = 0x014D;  // Sstc extension
inline constexpr uint16_t kCsrSatp     = 0x0180;

// Read-only identifiers
inline constexpr uint16_t kCsrMvendorid = 0x0F11;
inline constexpr uint16_t kCsrMarchid   = 0x0F12;
inline constexpr uint16_t kCsrMhartid   = 0x0F14;

// Read-only view over a RISC-V core's architectural state.  Used by the
// debugger's expression evaluator, watchpoints, and scoreboard CSR difftest
// so they can query either the DUT or the golden model without caring which
// concrete implementation sits underneath.
class CpuRegView {
 public:
  virtual ~CpuRegView() = default;

  CpuRegView(const CpuRegView&) = delete;
  CpuRegView& operator=(const CpuRegView&) = delete;

  virtual uint64_t pc() const = 0;
  virtual absl::StatusOr<uint64_t> gpr(int index) const = 0;

  // M-mode trap setup / handling
  virtual uint64_t mstatus() const = 0;
  virtual uint64_t mtvec() const = 0;
  virtual uint64_t mepc() const = 0;
  virtual uint64_t mcause() const = 0;
  virtual uint64_t mtval() const = 0;
  virtual uint64_t medeleg() const = 0;
  virtual uint64_t mideleg() const = 0;
  virtual uint64_t mie() const = 0;
  virtual uint64_t mscratch() const = 0;
  virtual uint64_t menvcfg() const = 0;
  virtual uint64_t mcounteren() const = 0;
  virtual uint64_t pmpcfg0() const = 0;
  virtual uint64_t pmpaddr0() const = 0;

  // S-mode trap setup / handling
  virtual uint64_t stvec() const = 0;
  virtual uint64_t sepc() const = 0;
  virtual uint64_t scause() const = 0;
  virtual uint64_t stval() const = 0;
  virtual uint64_t sscratch() const = 0;
  virtual uint64_t satp() const = 0;
  virtual uint64_t stimecmp() const = 0;

  // Read-only identifiers
  virtual uint64_t mvendorid() const = 0;
  virtual uint64_t marchid() const = 0;
  virtual uint64_t mhartid() const = 0;

  virtual absl::StatusOr<uint64_t> mem_load(uint64_t addr,
                                             uint8_t width) const = 0;

  // Resolve register value by name (e.g. "pc", "a0", "x10", "mtvec").
  absl::StatusOr<uint64_t> value(absl::string_view name) const;

 protected:
  CpuRegView() = default;
};

inline constexpr const char* kGprNames[] = {
    "$0",  "ra", "sp", "gp", "tp",  "t0",  "t1", "t2", "s0", "s1", "a0",
    "a1",  "a2", "a3", "a4", "a5",  "a6",  "a7", "s2", "s3", "s4", "s5",
    "s6",  "s7", "s8", "s9", "s10", "s11", "t3", "t4", "t5", "t6",
};

}  // namespace npc

#endif  // NPC_CPU_CPU_REG_VIEW_H_
