#include <npc/memory.hh>

namespace npc::mem {

PsramDevice g_psram;

word_t PsramDevice::read(paddr_t addr, int len) {
  auto off = range().offset(addr);
  word_t result = 0;
  for (int i = 0; i < len; i++)
    result |= static_cast<word_t>(storage_[off + i]) << (i * 8);
  return result;
}

void PsramDevice::write(paddr_t addr, int len, word_t data) {
  auto off = range().offset(addr);
  for (int i = 0; i < len; i++)
    storage_[off + i] = static_cast<uint8_t>((data >> (i * 8)) & 0xFF);
}

void PsramDevice::read_byte(int offset, char *out) {
  if (offset < 0 || offset >= static_cast<int>(CONFIG_SOC_PSRAM_SIZE)) {
    Log("Warning: PSRAM read out of bounds at offset 0x%08x", offset);
    *out = 0;
    return;
  }
  *out = static_cast<char>(storage_[offset]);
}

void PsramDevice::write_byte(int offset, char data) {
  if (offset < 0 || offset >= static_cast<int>(CONFIG_SOC_PSRAM_SIZE)) {
    Log("Warning: PSRAM write out of bounds at offset 0x%08x", offset);
    return;
  }
  storage_[offset] = static_cast<uint8_t>(data);
}

} // namespace npc::mem

// ======================== DPI-C wrappers ========================

extern "C" void psram_read(int addr, char *data) {
  npc::mem::g_psram.read_byte(addr, data);
}

extern "C" void psram_write(int addr, char data) {
  npc::mem::g_psram.write_byte(addr, data);
}

bool in_psram(paddr_t addr) {
  return npc::mem::g_psram.range().contains(addr);
}
