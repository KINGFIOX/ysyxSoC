#ifndef NPC_CPU_SPIKE_CPU_H_
#define NPC_CPU_SPIKE_CPU_H_

#include <cstdint>
#include <string>
#include <utility>

#include "absl/types/span.h"
#include "cpu/cpu_reg_view.h"

class sim_t;
class processor_t;
struct state_t;
class mmu_t;
class disassembler_t;

namespace npc {

class SpikeCpu final : public CpuRegView {
 public:
  explicit SpikeCpu(absl::Span<const uint8_t> flash_data);
  ~SpikeCpu() override;

  SpikeCpu(const SpikeCpu&) = delete;
  SpikeCpu& operator=(const SpikeCpu&) = delete;

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

  // Spike-specific write interface (used by the scoreboard to realign the
  // golden model's state against the DUT after each step).
  absl::Status set_pc(uint64_t value);
  absl::Status set_gpr(int index, uint64_t value);
  absl::Status set_mstatus(uint64_t value);
  absl::Status set_mtvec(uint64_t value);
  absl::Status set_mepc(uint64_t value);
  absl::Status set_mcause(uint64_t value);
  absl::Status set_mtval(uint64_t value);
  absl::Status set_medeleg(uint64_t value);
  absl::Status set_mideleg(uint64_t value);
  absl::Status set_mie(uint64_t value);
  absl::Status set_mscratch(uint64_t value);
  absl::Status set_menvcfg(uint64_t value);
  absl::Status set_mcounteren(uint64_t value);
  absl::Status set_pmpcfg0(uint64_t value);
  absl::Status set_pmpaddr0(uint64_t value);

  absl::Status set_stvec(uint64_t value);
  absl::Status set_sepc(uint64_t value);
  absl::Status set_scause(uint64_t value);
  absl::Status set_stval(uint64_t value);
  absl::Status set_sscratch(uint64_t value);
  absl::Status set_satp(uint64_t value);
  absl::Status set_stimecmp(uint64_t value);

  absl::Status set_mvendorid(uint64_t value);
  absl::Status set_marchid(uint64_t value);
  absl::Status set_mhartid(uint64_t value);

  absl::Status mem_store(uint64_t addr, uint64_t value, uint8_t width);

  void reset();
  absl::Status step();

  void raise_interrupt(uint64_t cause);

  std::pair<std::string, std::string> disasm(uint32_t inst) const;

 private:
  sim_t* sim_;
  processor_t* proc_;
  state_t* state_;
  mmu_t* mmu_;
  disassembler_t* disasm_;
};

}  // namespace npc

#endif  // NPC_CPU_SPIKE_CPU_H_
