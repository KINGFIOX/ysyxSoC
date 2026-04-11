#include "cpu/spike_core.h"

#include <cstdio>
#include <string>
#include <utility>
#include <vector>

#include "disasm.h"
#include "mmu.h"
#include "sim.h"
#include "trap.h"

namespace npc {

static constexpr const char* kIsa = "RV64IMAFDC";
static constexpr uint64_t kResetVector = 0x30000000;

static constexpr uint64_t kMemBases[] = {
    0x02000000,  // CLINT
    0x0c000000,  // PLIC
    0x0f000000,  // SRAM
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
    0x2000,      // SRAM
    0x1000,      // UART
    0x1000,      // SPI_CTRL
    0x200000,    // VGA
    0x10000000,  // XIP_FLASH
    0x10000000,  // SDRAM
    8,           // keyboard
};

struct SpikeCore::Impl {
  sim_t* sim;
  processor_t* proc;
  state_t* state;
  mmu_t* mmu;
  disassembler_t* disasm;
};

SpikeCore::SpikeCore(const uint8_t* flash_data, size_t flash_size)
    : impl_(new Impl) {
  static_assert(std::size(kMemBases) == std::size(kMemSizes));
  constexpr int kMemCount = std::size(kMemBases);

  std::vector<std::pair<reg_t, mem_t*>> mems;
  mems.reserve(kMemCount);
  for (int i = 0; i < kMemCount; ++i) {
    size_t aligned = (kMemSizes[i] + 0xFFF) & ~size_t{0xFFF};
    mems.emplace_back(reg_t(kMemBases[i]), new mem_t(aligned));
  }

  static debug_module_config_t dm_config = {
      2, 0, false, 0, true, true, true, true, true};

  auto* cfg = new cfg_t(
      std::make_pair(reg_t(0), reg_t(0)), nullptr, kIsa, DEFAULT_PRIV,
      DEFAULT_VARCH, false, endianness_little, 16, std::vector<mem_cfg_t>(),
      std::vector<size_t>(1), false, 4);

  std::vector<std::pair<reg_t, abstract_device_t*>> plugin_devices;
  std::vector<std::string> htif_args{""};

  impl_->sim = new sim_t(cfg, false, mems, plugin_devices, htif_args,
                          dm_config, nullptr, false, nullptr, false, nullptr,
                          true);

  impl_->proc = impl_->sim->get_core(0);
  impl_->state = impl_->proc->get_state();
  impl_->mmu = impl_->proc->get_mmu();
  impl_->disasm = new disassembler_t(&impl_->proc->get_isa());

  impl_->state->pc = kResetVector;

  for (size_t i = 0; i < flash_size; ++i) {
    uint64_t addr = kResetVector + i;
    (void)mem_store(addr, flash_data[i], 1);
  }
}

SpikeCore::~SpikeCore() {
  delete impl_->disasm;
  delete impl_->sim;
  delete impl_;
}

uint64_t SpikeCore::pc() const { return impl_->state->pc; }

void SpikeCore::set_pc(uint64_t value) { impl_->state->pc = value; }

uint64_t SpikeCore::gpr(int index) const { return impl_->state->XPR[index]; }

void SpikeCore::set_gpr(int index, uint64_t value) {
  impl_->state->XPR.write(index, value);
}

uint64_t SpikeCore::get_csr(uint16_t id) const {
  return impl_->proc->get_csr(id);
}

void SpikeCore::put_csr(uint16_t id, uint64_t value) {
  impl_->proc->put_csr(id, value);
}

SpikeResult SpikeCore::mem_load(uint64_t addr, uint8_t width) const {
  try {
    switch (width) {
      case 1:
        return SpikeResult::Ok(
            static_cast<uint64_t>(impl_->mmu->load<uint8_t>(addr)));
      case 2:
        return SpikeResult::Ok(
            static_cast<uint64_t>(impl_->mmu->load<uint16_t>(addr)));
      case 4:
        return SpikeResult::Ok(
            static_cast<uint64_t>(impl_->mmu->load<uint32_t>(addr)));
      case 8:
        return SpikeResult::Ok(impl_->mmu->load<uint64_t>(addr));
      default: {
        char buf[64];
        snprintf(buf, sizeof(buf), "invalid load width: %d", width);
        return SpikeResult::Err(buf);
      }
    }
  } catch (const trap_t&) {
    char buf[80];
    snprintf(buf, sizeof(buf), "spike trap: load u%d @ 0x%016lx",
             width * 8, static_cast<unsigned long>(addr));
    return SpikeResult::Err(buf);
  }
}

SpikeResult SpikeCore::mem_store(uint64_t addr, uint64_t value,
                                 uint8_t width) {
  try {
    switch (width) {
      case 1:
        impl_->mmu->store<uint8_t>(addr, static_cast<uint8_t>(value));
        break;
      case 2:
        impl_->mmu->store<uint16_t>(addr, static_cast<uint16_t>(value));
        break;
      case 4:
        impl_->mmu->store<uint32_t>(addr, static_cast<uint32_t>(value));
        break;
      case 8:
        impl_->mmu->store<uint64_t>(addr, value);
        break;
      default: {
        char buf[64];
        snprintf(buf, sizeof(buf), "invalid store width: %d", width);
        return SpikeResult::Err(buf);
      }
    }
    return SpikeResult::Ok();
  } catch (const trap_t&) {
    char buf[80];
    snprintf(buf, sizeof(buf), "spike trap: store w%d @ 0x%016lx",
             width, static_cast<unsigned long>(addr));
    return SpikeResult::Err(buf);
  }
}

SpikeResult SpikeCore::step() {
  try {
    impl_->proc->step(1);
    return SpikeResult::Ok();
  } catch (const trap_t&) {
    char buf[80];
    snprintf(buf, sizeof(buf), "spike trap during step (pc=0x%016lx)",
             static_cast<unsigned long>(impl_->state->pc));
    return SpikeResult::Err(buf);
  }
}

void SpikeCore::reset() { impl_->proc->reset(); }

std::pair<std::string, std::string> SpikeCore::disasm(uint32_t inst) const {
  insn_t insn(inst);
  const disasm_insn_t* match = impl_->disasm->lookup(insn);
  std::string mnemonic = (match != nullptr) ? match->get_name() : "";
  std::string full = impl_->disasm->disassemble(insn);
  return {mnemonic, full};
}

}  // namespace npc
