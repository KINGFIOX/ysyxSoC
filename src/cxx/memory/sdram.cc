#include <npc/memory.hh>

namespace npc::mem {

SdramDevice g_sdram;

word_t SdramDevice::read(paddr_t addr, int len) {
  auto off = range().offset(addr);
  word_t result = 0;
  for (int i = 0; i < len; i++)
    result |= static_cast<word_t>(storage_[off + i]) << (i * 8);
  return result;
}

void SdramDevice::write(paddr_t addr, int len, word_t data) {
  auto off = range().offset(addr);
  for (int i = 0; i < len; i++)
    storage_[off + i] = static_cast<uint8_t>((data >> (i * 8)) & 0xFF);
}

void SdramDevice::read_half(int offset, uint16_t *out) {
  if (offset < 0 || offset >= static_cast<int>(CONFIG_SOC_SDRAM_SIZE)) {
    Log("Warning: SDRAM read out of bounds at offset {:08x}", offset);
    *out = 0;
    return;
  }
  *out = static_cast<uint16_t>(storage_[offset]) |
         (static_cast<uint16_t>(storage_[offset + 1]) << 8);
}

void SdramDevice::write_byte(int offset, uint8_t data) {
  if (offset < 0 || offset >= static_cast<int>(CONFIG_SOC_SDRAM_SIZE)) {
    Log("Warning: SDRAM write out of bounds at offset {:08x}", offset);
    return;
  }
  storage_[offset] = data;
}

} // namespace npc::mem

// ======================== DPI-C wrappers ========================

extern "C" void sdram_read(int addr, uint16_t *ret) {
  npc::mem::g_sdram.read_half(addr, ret);
}

extern "C" uint16_t sdram_read_dpic(int addr) {
  uint16_t half;
  npc::mem::g_sdram.read_half(addr, &half);
  return half;
}

extern "C" void sdram_write(int addr, uint8_t data) {
  npc::mem::g_sdram.write_byte(addr, data);
}

bool in_sdram(paddr_t addr) {
  return npc::mem::g_sdram.range().contains(addr);
}
