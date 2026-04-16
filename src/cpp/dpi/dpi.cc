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

}  // extern "C"
