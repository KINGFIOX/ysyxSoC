/**
 * SRAM - external memory backed by Verilator's AXI4RAM internal array.
 * The pointer is set by core.cc via init_sram() after Verilator initialization.
 */

#include <npc/memory.hh>

namespace npc::mem {

SramDevice g_sram;

void SramDevice::bind(uint8_t *verilator_ptr) {
  ptr_ = verilator_ptr;
  Log("sram area [{:08x}, {:08x}], verilator ptr = {:p}",
      range().base,
      static_cast<paddr_t>(range().base + range().size - 1),
      static_cast<void *>(ptr_));
}

word_t SramDevice::read(paddr_t addr, int len) {
  if (unlikely(!ptr_)) {
    Log("Warning: sram_ptr not initialized");
    return 0xdeadbeef;
  }
  auto off = range().offset(addr);
  if (unlikely(off + len > CONFIG_SOC_SRAM_SIZE)) {
    Log("Warning: sram read out of range: {:08x}", addr);
    return 0xdeadbeef;
  }
  word_t data = 0;
  for (int i = 0; i < len; i++)
    data |= static_cast<word_t>(ptr_[off + i]) << (i * 8);
  return data;
}

void SramDevice::write(paddr_t addr, int len, word_t data) {
  if (!ptr_) {
    Log("Warning: sram_ptr not initialized");
    return;
  }
  auto off = range().offset(addr);
  if (off + len > CONFIG_SOC_SRAM_SIZE) {
    Log("Warning: sram write out of range: {:08x}", addr);
    return;
  }
  for (int i = 0; i < len; i++)
    ptr_[off + i] = static_cast<uint8_t>((data >> (i * 8)) & 0xFF);
}

} // namespace npc::mem

// ======================== C-compatible wrappers ========================

void init_sram(uint8_t *verilator_sram_ptr) {
  npc::mem::g_sram.bind(verilator_sram_ptr);
}

bool in_sram(paddr_t addr) {
  return npc::mem::g_sram.range().contains(addr);
}

extern "C" word_t sram_read(paddr_t addr, int len) {
  return npc::mem::g_sram.read(addr, len);
}

extern "C" void sram_write(paddr_t addr, int len, word_t data) {
  npc::mem::g_sram.write(addr, len, data);
}
