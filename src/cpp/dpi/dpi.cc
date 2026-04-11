#include <cstdint>
#include <cstring>

#include "absl/log/log.h"
#include "cpu/spike_cpu.h"
#include "dpi/memory.h"

// DPI-C callback functions called by Verilator during simulation.
// These must match the `import "DPI-C"` declarations in the SystemVerilog
// sources exactly. Verilator resolves them at link time.
//
// All addresses passed from RTL are device-relative offsets (base already
// stripped).

extern "C" {

void flash_read(int64_t addr, int8_t* data) {
  auto* mem = npc::g_memory;
  if (mem == nullptr) return;
  auto offset = static_cast<uint64_t>(addr);
  if (offset < mem->flash_size()) {
    *data = static_cast<int8_t>(mem->flash_data()[offset]);
  }
}

void sdram_read(int64_t addr, int16_t* data) {
  auto* mem = npc::g_memory;
  if (mem == nullptr) return;
  auto offset = static_cast<size_t>(static_cast<uint64_t>(addr));
  if (offset + 1 >= mem->sdram_size()) {
    LOG(WARNING) << absl::StreamFormat(
        "sdram_read OOB: offset=0x%x, abs=0x%x", offset,
        offset + npc::kSdramBase);
    *data = 0;
    return;
  }
  uint8_t* base = mem->sdram_data();
  uint16_t lo = base[offset];
  uint16_t hi = base[offset + 1];
  *data = static_cast<int16_t>(lo | (hi << 8));
}

void sdram_write(int64_t addr, uint8_t data) {
  auto* mem = npc::g_memory;
  if (mem == nullptr) return;
  auto offset = static_cast<size_t>(static_cast<uint64_t>(addr));
  if (offset >= mem->sdram_size()) {
    LOG(WARNING) << absl::StreamFormat(
        "sdram_write OOB: offset=0x%x, abs=0x%x, data=0x%02x", offset,
        offset + npc::kSdramBase, data);
    return;
  }
  mem->sdram_data()[offset] = data;
}

// ============ Spike Frontend (DPI chandle) ============

void spike_fe_new(int64_t* out) {
  auto* mem = npc::g_memory;
  if (mem == nullptr) {
    *out = 0;
    return;
  }
  absl::Span<const uint8_t> flash(mem->flash_data(), mem->flash_size());
  auto* spike = new npc::SpikeCpu(flash);
  *out = reinterpret_cast<int64_t>(spike);
}

void spike_fe_fetch_and_step(int64_t handle, uint32_t* out) {
  auto* spike = reinterpret_cast<npc::SpikeCpu*>(handle);
  uint64_t pc_val = spike->pc();
  auto inst_or = spike->mem_load(pc_val, 4);
  if (!inst_or.ok()) {
    std::memset(out, 0, 4 * sizeof(uint32_t));
    return;
  }
  uint32_t inst = static_cast<uint32_t>(*inst_or);
  if (!spike->step().ok()) {
    std::memset(out, 0, 4 * sizeof(uint32_t));
    return;
  }
  uint64_t npc = spike->pc();
  out[0] = 1;  // ok
  out[1] = inst;
  out[2] = static_cast<uint32_t>(npc);
  out[3] = static_cast<uint32_t>(npc >> 32);
}

void spike_fe_set_gpr(int64_t handle, int32_t idx, int64_t val) {
  auto* spike = reinterpret_cast<npc::SpikeCpu*>(handle);
  (void)spike->set_gpr(idx, static_cast<uint64_t>(val));
}

void spike_fe_set_pc(int64_t handle, int64_t pc_val) {
  auto* spike = reinterpret_cast<npc::SpikeCpu*>(handle);
  (void)spike->set_pc(static_cast<uint64_t>(pc_val));
}

}  // extern "C"
