#ifndef NPC_CPU_ABSTRACT_CPU_H_
#define NPC_CPU_ABSTRACT_CPU_H_

#include <cstdint>

#include "absl/status/status.h"
#include "absl/status/statusor.h"
#include "absl/strings/string_view.h"

namespace npc {

inline constexpr uint16_t kCsrMstatus = 0x0300;
inline constexpr uint16_t kCsrMtvec = 0x0305;
inline constexpr uint16_t kCsrMepc = 0x0341;
inline constexpr uint16_t kCsrMcause = 0x0342;
inline constexpr uint16_t kCsrMtval = 0x0343;
inline constexpr uint16_t kCsrMvendorid = 0x0F11;
inline constexpr uint16_t kCsrMarchid = 0x0F12;

class AbstractCpu {
 public:
  virtual ~AbstractCpu() = default;

  AbstractCpu(const AbstractCpu&) = delete;
  AbstractCpu& operator=(const AbstractCpu&) = delete;

  virtual uint64_t pc() const = 0;
  virtual absl::Status set_pc(uint64_t value) = 0;

  virtual absl::StatusOr<uint64_t> gpr(int index) const = 0;
  virtual absl::Status set_gpr(int index, uint64_t value) = 0;

  virtual uint64_t mstatus() const = 0;
  virtual absl::Status set_mstatus(uint64_t value) = 0;
  virtual uint64_t mtvec() const = 0;
  virtual absl::Status set_mtvec(uint64_t value) = 0;
  virtual uint64_t mepc() const = 0;
  virtual absl::Status set_mepc(uint64_t value) = 0;
  virtual uint64_t mcause() const = 0;
  virtual absl::Status set_mcause(uint64_t value) = 0;
  virtual uint64_t mtval() const = 0;
  virtual absl::Status set_mtval(uint64_t value) = 0;
  virtual uint64_t mvendorid() const = 0;
  virtual absl::Status set_mvendorid(uint64_t value) = 0;
  virtual uint64_t marchid() const = 0;
  virtual absl::Status set_marchid(uint64_t value) = 0;

  virtual absl::StatusOr<uint64_t> mem_load(uint64_t addr,
                                             uint8_t width) const = 0;
  virtual absl::Status mem_store(uint64_t addr, uint64_t value,
                                 uint8_t width) = 0;

  virtual void reset() = 0;
  virtual absl::Status step() = 0;

  // Resolve register value by name (e.g. "pc", "a0", "x10", "mtvec").
  absl::StatusOr<uint64_t> value(absl::string_view name) const;

 protected:
  AbstractCpu() = default;
};

inline constexpr const char* kGprNames[] = {
    "$0",  "ra", "sp", "gp", "tp",  "t0",  "t1", "t2", "s0", "s1", "a0",
    "a1",  "a2", "a3", "a4", "a5",  "a6",  "a7", "s2", "s3", "s4", "s5",
    "s6",  "s7", "s8", "s9", "s10", "s11", "t3", "t4", "t5", "t6",
};

}  // namespace npc

#endif  // NPC_CPU_ABSTRACT_CPU_H_
