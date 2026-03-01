#include <npc/memory.hh>
#include <npc/isa.hh>
#include <npc/cpu_model.hh>

namespace npc::mem {

AddressSpace g_address_space;

void AddressSpace::register_device(MemoryDevice *dev) {
  Assert(count_ < kMaxDevices, "too many memory devices registered");
  devices_[count_++] = dev;
  Log("registered memory device: {} [{:08x}, {:08x}]",
      dev->name(),
      dev->range().base,
      static_cast<paddr_t>(dev->range().base + dev->range().size - 1));
}

MemoryDevice *AddressSpace::find(paddr_t addr) const {
  for (int i = 0; i < count_; ++i) {
    if (devices_[i]->range().contains(addr)) return devices_[i];
  }
  return nullptr;
}

word_t AddressSpace::read(paddr_t addr, int len) {
  auto *dev = find(addr);
  if (dev) return dev->read(addr, len);
  panic("address = " FMT_PADDR " is out of bound at pc = " FMT_WORD, addr,
        npc::dut().pc());
  return 0;
}

void AddressSpace::write(paddr_t addr, int len, word_t data) {
  auto *dev = find(addr);
  if (dev) {
    dev->write(addr, len, data);
    return;
  }
  panic("address = " FMT_PADDR " is out of bound at pc = " FMT_WORD, addr,
        npc::dut().pc());
}

} // namespace npc::mem

using namespace npc::mem;

static void setup_address_space() {
  g_address_space.register_device(&g_flash);
  g_address_space.register_device(&g_psram);
  g_address_space.register_device(&g_sdram);
  g_address_space.register_device(&g_sram);
  g_address_space.register_device(&g_mrom);
}

void init_mem() { setup_address_space(); }

word_t paddr_read(paddr_t addr, int len) {
  return g_address_space.read(addr, len);
}

void paddr_write(paddr_t addr, int len, word_t data) {
  g_address_space.write(addr, len, data);
}
