#pragma once

#include <cstddef>
#include <span>
#include <npc/common.hh>
#include <npc/isa_traits.hh>

namespace npc {

struct StepResult {
  word_t pc;
  word_t dnpc;
  uint32_t inst;
  bool is_mmio;
};

class CpuModel {
public:
  virtual ~CpuModel() = default;

  virtual bool init() = 0;
  virtual void fini() = 0;

  virtual StepResult step() = 0;

  [[nodiscard]] virtual word_t pc() const = 0;
  virtual void set_pc(word_t v) = 0;

  [[nodiscard]] virtual word_t gpr(int i) const = 0;
  virtual void set_gpr(int i, word_t v) = 0;

  [[nodiscard]] virtual std::span<const word_t, num_gprs> gprs() const = 0;
  virtual std::span<word_t, num_gprs> gprs_mut() = 0;

  [[nodiscard]] virtual word_t mstatus() const = 0;
  virtual void set_mstatus(word_t v) = 0;
  [[nodiscard]] virtual word_t mtvec() const = 0;
  virtual void set_mtvec(word_t v) = 0;
  [[nodiscard]] virtual word_t mepc() const = 0;
  virtual void set_mepc(word_t v) = 0;
  [[nodiscard]] virtual word_t mcause() const = 0;
  virtual void set_mcause(word_t v) = 0;
  [[nodiscard]] virtual word_t mtval() const = 0;
  virtual void set_mtval(word_t v) = 0;
  [[nodiscard]] virtual word_t mvendorid() const = 0;
  [[nodiscard]] virtual word_t marchid() const = 0;

  virtual void memcpy(paddr_t addr, void *buf, size_t n, bool direction) = 0;

  virtual void raise_intr(word_t no, word_t tval) { // NOLINT
    (void)no;
    (void)tval;
    panic("raise_intr not supported by this model");
  }

  virtual void display() const = 0;
};

CpuModel &dut();
void set_dut(CpuModel *model);

CpuModel &ref();
void set_ref(CpuModel *model);

} // namespace npc
