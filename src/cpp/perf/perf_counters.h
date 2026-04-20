#ifndef NPC_PERF_PERF_COUNTERS_H_
#define NPC_PERF_PERF_COUNTERS_H_

#include <array>
#include <cstdint>
#include <string>

namespace npc::perf {

// Keep in sync with npc/src/scala/core/common/PerfEvent.scala::PerfEvent
enum EventId : uint8_t {
  // Cache / frontend events
  kIcacheAccess      = 0,
  kIcacheHit         = 1,
  kIcacheMiss        = 2,
  kDcacheAccess      = 3,
  kDcacheHit         = 4,
  kDcacheMiss        = 5,
  kIfuOutValid       = 6,
  kIfuStall          = 7,
  kIcacheMissCycles  = 8,
  kDcacheMissCycles  = 9,

  // Commit-stage events (one pulse per committed instruction).
  kCommit            = 10,
  kBranch            = 11,
  kBranchMispredict  = 12,
  kFlush             = 13,
  kCommitAlu         = 14,  // R_ALU | I_ALU | LUI | AUIPC
  kCommitMulDiv      = 15,  // MUL / DIV / REM
  kCommitLoad        = 16,
  kCommitStore       = 17,
  kCommitCfBranch    = 18,  // conditional BRANCH
  kCommitCfJal       = 19,
  kCommitCfJalr      = 20,
  kCommitCsr         = 21,
  kCommitSystem      = 22,  // ECALL/EBREAK/MRET/SRET/SFENCE_VMA
  kCommitFence       = 23,  // FENCE | FENCE_I

  // Frontend pipeline events (Step E).
  kFeRedirectBranch  = 24,  // F3 self-redirect fired because of taken branch/jump
  // 25, 26 intentionally left unused (formerly kFeRedirectSerial and
  // kFeWaitFlushStall; removed when the ICache began handling fence.i
  // drain itself).  Not reassigned to keep DPI-C IDs stable.
  kFeF1TlbMiss       = 27,  // F1 TLB miss pulse (one per cycle spent missing)
  kFeF2IcacheWait    = 28,  // F2 req pending but cache/Fp not accepting
  kFeFpRespWait      = 29,  // Fp inflight waiting for rdata

  kNumEvents
};

// Accumulates DPI-C perf events emitted by the RTL each cycle.
// Single process-wide instance; all events funnel through npc_perf_event().
//
// Cycle count, wall time, and benchmark name are still supplied out-of-band
// by the driver (main.cc) because they come from the C++ simulation host,
// not from the RTL.
class PerfCounters {
 public:
  static PerfCounters& Instance();

  void Increment(uint8_t id) {
    if (id < kNumEvents) ++counts_[id];
  }
  uint64_t Get(uint8_t id) const {
    return id < kNumEvents ? counts_[id] : 0;
  }

  // External info supplied by the driver (main.cc).
  void set_cycles(uint64_t c) { cycles_ = c; }
  uint64_t cycles() const { return cycles_; }

  void set_benchmark_name(const std::string& n) { bench_name_ = n; }
  const std::string& benchmark_name() const { return bench_name_; }

  void set_elapsed_us(uint64_t e) { elapsed_us_ = e; }
  uint64_t elapsed_us() const { return elapsed_us_; }

  // Convenience accessors derived from event counters.
  uint64_t commit() const { return Get(kCommit); }
  uint64_t branch() const { return Get(kBranch); }
  uint64_t branch_mispredict() const { return Get(kBranchMispredict); }
  uint64_t flush() const { return Get(kFlush); }

  // Print a human-readable table to stdout (and LOG).
  void ReportStdout() const;
  // Dump JSON to path; returns true on success.
  bool DumpJson(const std::string& path) const;

 private:
  PerfCounters() = default;
  std::array<uint64_t, kNumEvents> counts_{};
  uint64_t cycles_ = 0;
  std::string bench_name_;
  uint64_t elapsed_us_ = 0;
};

}  // namespace npc::perf

#endif  // NPC_PERF_PERF_COUNTERS_H_
