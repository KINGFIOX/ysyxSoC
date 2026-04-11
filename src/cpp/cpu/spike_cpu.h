#ifndef NPC_CPU_SPIKE_CPU_H_
#define NPC_CPU_SPIKE_CPU_H_

#include <cstdint>
#include <memory>
#include <string>
#include <utility>

#include "absl/types/span.h"
#include "cpu/abstract_cpu.h"

namespace npc {

class SpikeCore;

class SpikeCpu final : public AbstractCpu {
 public:
  explicit SpikeCpu(absl::Span<const uint8_t> flash_data);
  ~SpikeCpu() override;

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

  // Returns (mnemonic, full_disassembly).
  std::pair<std::string, std::string> disasm(uint32_t inst) const;

 private:
  std::unique_ptr<SpikeCore> core_;
};

}  // namespace npc

#endif  // NPC_CPU_SPIKE_CPU_H_
