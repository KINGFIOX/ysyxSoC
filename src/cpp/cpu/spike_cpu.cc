#include "cpu/spike_cpu.h"

#include "cpu/spike_core.h"

namespace npc {

SpikeCpu::SpikeCpu(absl::Span<const uint8_t> flash_data)
    : core_(std::make_unique<SpikeCore>(flash_data.data(),
                                        flash_data.size())) {}

SpikeCpu::~SpikeCpu() = default;

uint64_t SpikeCpu::pc() const { return core_->pc(); }

absl::Status SpikeCpu::set_pc(uint64_t value) {
  if (value % 4 != 0) {
    return absl::InvalidArgumentError("pc must be aligned to 4 bytes");
  }
  core_->set_pc(value);
  return absl::OkStatus();
}

absl::StatusOr<uint64_t> SpikeCpu::gpr(int index) const {
  if (index < 0 || index >= 32) {
    return absl::InvalidArgumentError("invalid register index");
  }
  return core_->gpr(index);
}

absl::Status SpikeCpu::set_gpr(int index, uint64_t value) {
  if (index < 0 || index >= 32) {
    return absl::InvalidArgumentError("invalid register index");
  }
  core_->set_gpr(index, value);
  return absl::OkStatus();
}

uint64_t SpikeCpu::mstatus() const { return core_->get_csr(kCsrMstatus); }
absl::Status SpikeCpu::set_mstatus(uint64_t v) {
  core_->put_csr(kCsrMstatus, v);
  return absl::OkStatus();
}

uint64_t SpikeCpu::mtvec() const { return core_->get_csr(kCsrMtvec); }
absl::Status SpikeCpu::set_mtvec(uint64_t v) {
  core_->put_csr(kCsrMtvec, v);
  return absl::OkStatus();
}

uint64_t SpikeCpu::mepc() const { return core_->get_csr(kCsrMepc); }
absl::Status SpikeCpu::set_mepc(uint64_t v) {
  core_->put_csr(kCsrMepc, v);
  return absl::OkStatus();
}

uint64_t SpikeCpu::mcause() const { return core_->get_csr(kCsrMcause); }
absl::Status SpikeCpu::set_mcause(uint64_t v) {
  core_->put_csr(kCsrMcause, v);
  return absl::OkStatus();
}

uint64_t SpikeCpu::mtval() const { return core_->get_csr(kCsrMtval); }
absl::Status SpikeCpu::set_mtval(uint64_t v) {
  core_->put_csr(kCsrMtval, v);
  return absl::OkStatus();
}

uint64_t SpikeCpu::mvendorid() const {
  return core_->get_csr(kCsrMvendorid);
}
absl::Status SpikeCpu::set_mvendorid(uint64_t v) {
  core_->put_csr(kCsrMvendorid, v);
  return absl::OkStatus();
}

uint64_t SpikeCpu::marchid() const { return core_->get_csr(kCsrMarchid); }
absl::Status SpikeCpu::set_marchid(uint64_t v) {
  core_->put_csr(kCsrMarchid, v);
  return absl::OkStatus();
}

absl::StatusOr<uint64_t> SpikeCpu::mem_load(uint64_t addr,
                                             uint8_t width) const {
  auto r = core_->mem_load(addr, width);
  if (!r.ok) return absl::InternalError(r.error);
  return r.value;
}

absl::Status SpikeCpu::mem_store(uint64_t addr, uint64_t value,
                                 uint8_t width) {
  auto r = core_->mem_store(addr, value, width);
  if (!r.ok) return absl::InternalError(r.error);
  return absl::OkStatus();
}

void SpikeCpu::reset() { core_->reset(); }

absl::Status SpikeCpu::step() {
  auto r = core_->step();
  if (!r.ok) return absl::InternalError(r.error);
  return absl::OkStatus();
}

std::pair<std::string, std::string> SpikeCpu::disasm(uint32_t inst) const {
  return core_->disasm(inst);
}

}  // namespace npc
