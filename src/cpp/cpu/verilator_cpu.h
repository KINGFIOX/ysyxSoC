#ifndef NPC_CPU_VERILATOR_CPU_H_
#define NPC_CPU_VERILATOR_CPU_H_

#include <climits>
#include <cstdint>
#include <memory>

#include "absl/types/span.h"
#include "cpu/abstract_cpu.h"
#include "dpi/memory.h"

class VerilatedContext;
class VerilatedFstC;
class VNPCSoC;

namespace nvboard {
class Board;
}

namespace npc {

inline constexpr int kResetCycles = 15;
inline constexpr int kMaxStepCycles = 100000;

// Software UART TX line decoder.  Auto-detects baud rate by tracking the
// minimum pulse width on the TX line, then decodes 8N1 frames and forwards
// each byte to the NVBoard console via Board::Uart::Putchar().
class UartTxDecoder {
 public:
  void Tick(bool tx, nvboard::Board* board);

 private:
  enum State { kIdle, kReceiving };
  State state_ = kIdle;

  bool prev_tx_ = true;     // TX idles high
  int pulse_cycles_ = 0;    // cycles spent in the current TX level
  int min_pulse_ = INT_MAX; // shortest observed pulse → 1 bit period

  int counter_ = 0;         // cycles since start-bit falling edge
  int bit_index_ = 0;
  uint8_t byte_ = 0;
};

class VerilatorCpu final : public AbstractCpu {
 public:
  VerilatorCpu(absl::Span<const uint8_t> flash_data, bool nvboard);
  ~VerilatorCpu() override;

  // Simulation control
  uint64_t sim_time() const { return sim_time_; }
  absl::Status run_until(uint64_t target_sim_time);
  void enable_wave();
  void flush_wave();

  // Probe signals from RTL
  bool is_mmio() const;
  uint64_t dnpc() const;
  uint32_t inst() const;

  // Performance counters
  uint32_t perf_commit_cnt() const;
  uint32_t perf_branch_cnt() const;
  uint32_t perf_branch_mispredict_cnt() const;
  uint32_t perf_flush_cnt() const;

  // AbstractCpu interface
  uint64_t pc() const override;
  absl::Status set_pc(uint64_t value) override;
  absl::StatusOr<uint64_t> gpr(int index) const override;
  absl::Status set_gpr(int index, uint64_t value) override;

  uint64_t mstatus() const override;
  absl::Status set_mstatus(uint64_t value) override;
  uint64_t mtvec() const override;
  absl::Status set_mtvec(uint64_t value) override;
  uint64_t mepc() const override;
  absl::Status set_mepc(uint64_t value) override;
  uint64_t mcause() const override;
  absl::Status set_mcause(uint64_t value) override;
  uint64_t mtval() const override;
  absl::Status set_mtval(uint64_t value) override;
  uint64_t mvendorid() const override;
  absl::Status set_mvendorid(uint64_t value) override;
  uint64_t marchid() const override;
  absl::Status set_marchid(uint64_t value) override;

  absl::StatusOr<uint64_t> mem_load(uint64_t addr,
                                     uint8_t width) const override;
  absl::Status mem_store(uint64_t addr, uint64_t value,
                         uint8_t width) override;
  void reset() override;
  absl::Status step() override;

 private:
  absl::Status tick();

  std::unique_ptr<VerilatedContext> ctx_;
  VNPCSoC* top_;  // owned, but deleted manually via top_->final() + delete
  VerilatedFstC* tfp_ = nullptr;
  Memory mem_;
  uint64_t sim_time_ = 0;
  std::unique_ptr<nvboard::Board> nvboard_;
  UartTxDecoder uart_decoder_;
};

}  // namespace npc

#endif  // NPC_CPU_VERILATOR_CPU_H_
