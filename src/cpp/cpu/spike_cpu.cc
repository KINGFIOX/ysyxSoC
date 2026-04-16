#include "cpu/spike_cpu.h"

#include <cstdio>
#include <format>
#include <string>
#include <utility>
#include <vector>

#include "absl/strings/str_format.h"
#include "riscv/disasm.h"
#include "riscv/mmu.h"
#include "riscv/sim.h"
#include "riscv/trap.h"

namespace npc {

static constexpr const char* kIsa = "RV64IMAFDC";
static constexpr uint64_t kResetVector = 0x80000000;

static constexpr uint64_t kMemBases[] = {
    0x02000000,  // CLINT
    0x0c000000,  // PLIC
    0x10000000,  // UART
    0x10001000,  // SPI_CTRL
    0x21000000,  // VGA
    0x30000000,  // XIP_FLASH
    0x80000000,  // SDRAM
    0x10011000,  // keyboard
};

static constexpr uint64_t kMemSizes[] = {
    0x10000,     // CLINT
    0x400000,    // PLIC
    0x1000,      // UART
    0x1000,      // SPI_CTRL
    0x200000,    // VGA
    0x10000000,  // XIP_FLASH
    0x10000000,  // SDRAM
    8,           // keyboard
};

SpikeCpu::SpikeCpu(absl::Span<const uint8_t> flash_data) {
  static_assert(std::size(kMemBases) == std::size(kMemSizes));
  constexpr int kMemCount = std::size(kMemBases);

  std::vector<std::pair<reg_t, mem_t*>> mems;
  mems.reserve(kMemCount);
  for (int i = 0; i < kMemCount; ++i) {
    size_t aligned = (kMemSizes[i] + 0xFFF) & ~size_t{0xFFF};
    mems.emplace_back(static_cast<reg_t>(kMemBases[i]), new mem_t(aligned));
  }

  static debug_module_config_t dm_config = {
      2, 0, false, 0, true, true, true, true, true};

  auto* cfg = new cfg_t(
      std::make_pair(static_cast<reg_t>(0), static_cast<reg_t>(0)), nullptr, kIsa, DEFAULT_PRIV,
      DEFAULT_VARCH, false, endianness_little, 16, std::vector<mem_cfg_t>(),
      std::vector<size_t>(1), false, 4);

  std::vector<std::pair<reg_t, abstract_device_t*>> plugin_devices;
  std::vector<std::string> htif_args{""};

  sim_ = new sim_t(cfg, false, mems, plugin_devices, htif_args, dm_config,
                    nullptr, false, nullptr, false, nullptr, true);

  proc_ = sim_->get_core(0);
  state_ = proc_->get_state();
  mmu_ = proc_->get_mmu();
  disasm_ = new disassembler_t(&proc_->get_isa());

  state_->pc = kResetVector;

  for (size_t i = 0; i < flash_data.size(); ++i) {
    uint64_t addr = kResetVector + i;
    (void)mem_store(addr, flash_data[i], 1);
  }
}

SpikeCpu::~SpikeCpu() {
  delete disasm_;
  delete sim_;
}

uint64_t SpikeCpu::pc() const { return state_->pc; }

absl::Status SpikeCpu::set_pc(uint64_t value) {
  if (value % 4 != 0) {
    return absl::InvalidArgumentError("pc must be aligned to 4 bytes");
  }
  state_->pc = value;
  return absl::OkStatus();
}

absl::StatusOr<uint64_t> SpikeCpu::gpr(int index) const {
  if (index < 0 || index >= 32) {
    return absl::InvalidArgumentError("invalid register index");
  }
  return state_->XPR[index];
}

absl::Status SpikeCpu::set_gpr(int index, uint64_t value) {
  if (index < 0 || index >= 32) {
    return absl::InvalidArgumentError("invalid register index");
  }
  state_->XPR.write(index, value);
  return absl::OkStatus();
}

uint64_t SpikeCpu::mstatus() const { return proc_->get_csr(kCsrMstatus); }
absl::Status SpikeCpu::set_mstatus(uint64_t v) {
  proc_->put_csr(kCsrMstatus, v);
  return absl::OkStatus();
}

uint64_t SpikeCpu::mtvec() const { return proc_->get_csr(kCsrMtvec); }
absl::Status SpikeCpu::set_mtvec(uint64_t v) {
  proc_->put_csr(kCsrMtvec, v);
  return absl::OkStatus();
}

uint64_t SpikeCpu::mepc() const { return proc_->get_csr(kCsrMepc); }
absl::Status SpikeCpu::set_mepc(uint64_t v) {
  proc_->put_csr(kCsrMepc, v);
  return absl::OkStatus();
}

uint64_t SpikeCpu::mcause() const { return proc_->get_csr(kCsrMcause); }
absl::Status SpikeCpu::set_mcause(uint64_t v) {
  proc_->put_csr(kCsrMcause, v);
  return absl::OkStatus();
}

uint64_t SpikeCpu::mtval() const { return proc_->get_csr(kCsrMtval); }
absl::Status SpikeCpu::set_mtval(uint64_t v) {
  proc_->put_csr(kCsrMtval, v);
  return absl::OkStatus();
}

uint64_t SpikeCpu::mvendorid() const {
  return proc_->get_csr(kCsrMvendorid);
}
absl::Status SpikeCpu::set_mvendorid(uint64_t v) {
  proc_->put_csr(kCsrMvendorid, v);
  return absl::OkStatus();
}

uint64_t SpikeCpu::marchid() const { return proc_->get_csr(kCsrMarchid); }
absl::Status SpikeCpu::set_marchid(uint64_t v) {
  proc_->put_csr(kCsrMarchid, v);
  return absl::OkStatus();
}

absl::StatusOr<uint64_t> SpikeCpu::mem_load(uint64_t addr,
                                             uint8_t width) const {
  try {
    switch (width) {
      case 1:
        return static_cast<uint64_t>(mmu_->load<uint8_t>(addr));
      case 2:
        return static_cast<uint64_t>(mmu_->load<uint16_t>(addr));
      case 4:
        return static_cast<uint64_t>(mmu_->load<uint32_t>(addr));
      case 8:
        return mmu_->load<uint64_t>(addr);
      default:
        return absl::InvalidArgumentError(
            std::format("invalid load width: %d", width));
    }
  } catch (const trap_t&) {
    return absl::InternalError(
        std::format("spike trap: load u%d @ 0x%016x", width * 8, addr));
  }
}

absl::Status SpikeCpu::mem_store(uint64_t addr, uint64_t value,
                                  uint8_t width) {
  try {
    switch (width) {
      case 1:
        mmu_->store<uint8_t>(addr, static_cast<uint8_t>(value));
        break;
      case 2:
        mmu_->store<uint16_t>(addr, static_cast<uint16_t>(value));
        break;
      case 4:
        mmu_->store<uint32_t>(addr, static_cast<uint32_t>(value));
        break;
      case 8:
        mmu_->store<uint64_t>(addr, value);
        break;
      default:
        return absl::InvalidArgumentError(
            std::format("invalid store width: %d", width));
    }
    return absl::OkStatus();
  } catch (const trap_t&) {
    return absl::InternalError(
        std::format("spike trap: store w%d @ 0x%016x", width, addr));
  }
}

void SpikeCpu::reset() { proc_->reset(); }

absl::Status SpikeCpu::step() {
  try {
    proc_->step(1);
    return absl::OkStatus();
  } catch (const trap_t&) {
    return absl::InternalError(
        std::format("spike trap during step (pc=0x%016x)", state_->pc));
  }
}

std::pair<std::string, std::string> SpikeCpu::disasm(uint32_t inst) const {
  insn_t insn(inst);
  const disasm_insn_t* match = disasm_->lookup(insn);
  std::string mnemonic = (match != nullptr) ? match->get_name() : "";
  std::string full = disasm_->disassemble(insn);
  return {mnemonic, full};
}

}  // namespace npc
