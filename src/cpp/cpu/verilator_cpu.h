#ifndef NPC_CPU_VERILATOR_CPU_H_
#define NPC_CPU_VERILATOR_CPU_H_

#include <cstdint>
#include <memory>

#include "absl/types/span.h"
#include "cpu/cpu_reg_view.h"
#include "dpi/memory.h"

class VerilatedContext;
class VerilatedVcdC;
class VNPCSoC;

namespace nvboard {
class Board;
}

namespace npc {

inline constexpr int kResetCycles = 15;
inline constexpr int kMaxStepCycles = 100000;

class VerilatorCpu final : public CpuRegView {
 public:
  VerilatorCpu(absl::Span<const uint8_t> flash_data, bool nvboard);
  ~VerilatorCpu() override;

  // Simulation control
  uint64_t sim_time() const { return sim_time_; }
  // Number of full clock cycles after reset completed (1 cycle = 2 sim_time).
  uint64_t cycle_count() const { return cycle_count_; }
  absl::Status run_until(uint64_t target_sim_time);
  void enable_wave(uint64_t tail = 0);

  void reset();
  absl::Status step();

  // Probe signals from RTL
  bool is_mmio() const;
  uint64_t dnpc() const;
  uint32_t inst() const;

  // Perf counters are now routed entirely through DPI-C events; see
  // npc/src/cpp/perf/perf_counters.h.

  // CpuRegView interface (read-only)
  uint64_t pc() const override;
  absl::StatusOr<uint64_t> gpr(int index) const override;

  uint64_t mstatus() const override;
  uint64_t mtvec() const override;
  uint64_t mepc() const override;
  uint64_t mcause() const override;
  uint64_t mtval() const override;
  uint64_t medeleg() const override;
  uint64_t mideleg() const override;
  uint64_t mie() const override;
  uint64_t mscratch() const override;
  uint64_t menvcfg() const override;
  uint64_t mcounteren() const override;
  uint64_t pmpcfg0() const override;
  uint64_t pmpaddr0() const override;

  uint64_t stvec() const override;
  uint64_t sepc() const override;
  uint64_t scause() const override;
  uint64_t stval() const override;
  uint64_t sscratch() const override;
  uint64_t satp() const override;
  uint64_t stimecmp() const override;

  uint64_t mvendorid() const override;
  uint64_t marchid() const override;
  uint64_t mhartid() const override;

  absl::StatusOr<uint64_t> mem_load(uint64_t addr,
                                     uint8_t width) const override;

 private:
  absl::Status tick();

  std::unique_ptr<VerilatedContext> ctx_;
  VNPCSoC* top_;  // owned, but deleted manually via top_->final() + delete
  VerilatedVcdC* tfp_ = nullptr;
  Memory mem_;
  uint64_t sim_time_ = 0;
  uint64_t cycle_count_ = 0;
  uint64_t wave_tail_ = 0;
  uint64_t wave_cycle_ = 0;
  std::unique_ptr<nvboard::Board> nvboard_;
};

}  // namespace npc

#endif  // NPC_CPU_VERILATOR_CPU_H_
