#ifndef NPC_MEMORY_H_
#define NPC_MEMORY_H_

#include <npc/common.hh>
#include <cstddef>
#include <cstdint>
#include <string_view>

namespace npc::mem {

struct AddressRange {
  paddr_t base;
  size_t size;
  constexpr bool contains(paddr_t addr) const {
    return addr >= base && addr < base + size;
  }
  constexpr paddr_t offset(paddr_t addr) const { return addr - base; }
};

class MemoryDevice {
public:
  virtual ~MemoryDevice() = default;
  virtual std::string_view name() const = 0;
  virtual AddressRange range() const = 0;
  virtual word_t read(paddr_t addr, int len) = 0;
  virtual void write(paddr_t addr, int len, word_t data) = 0;
};

// ======================== AddressSpace ========================

class AddressSpace {
  static constexpr int kMaxDevices = 8;
  MemoryDevice *devices_[kMaxDevices]{};
  int count_ = 0;

public:
  void register_device(MemoryDevice *dev);
  MemoryDevice *find(paddr_t addr) const;
  word_t read(paddr_t addr, int len);
  void write(paddr_t addr, int len, word_t data);
};

// ======================== Concrete Devices ========================

class FlashDevice : public MemoryDevice {
  uint8_t *mem_ = nullptr;
  long loaded_size_ = 0;

public:
  ~FlashDevice() override;
  std::string_view name() const override { return "flash"; }
  AddressRange range() const override {
    return {CONFIG_SOC_XIP_FLASH_BASE, CONFIG_SOC_XIP_FLASH_SIZE};
  }
  word_t read(paddr_t addr, int len) override;
  void write(paddr_t addr, int len, word_t data) override;
  long load_image(const char *path);
  uint8_t *data() { return mem_; }
  void read_byte(int offset, char *out);
};

class PsramDevice : public MemoryDevice {
  uint8_t storage_[CONFIG_SOC_PSRAM_SIZE]{};

public:
  std::string_view name() const override { return "psram"; }
  AddressRange range() const override {
    return {CONFIG_SOC_PSRAM_BASE, CONFIG_SOC_PSRAM_SIZE};
  }
  word_t read(paddr_t addr, int len) override;
  void write(paddr_t addr, int len, word_t data) override;
  void read_byte(int offset, char *out);
  void write_byte(int offset, char data);
};

class SdramDevice : public MemoryDevice {
  uint8_t storage_[CONFIG_SOC_SDRAM_SIZE]{};

public:
  std::string_view name() const override { return "sdram"; }
  AddressRange range() const override {
    return {CONFIG_SOC_SDRAM_BASE, CONFIG_SOC_SDRAM_SIZE};
  }
  word_t read(paddr_t addr, int len) override;
  void write(paddr_t addr, int len, word_t data) override;
  void read_half(int offset, uint16_t *out);
  void write_byte(int offset, uint8_t data);
};

class SramDevice : public MemoryDevice {
  uint8_t *ptr_ = nullptr;

public:
  std::string_view name() const override { return "sram"; }
  AddressRange range() const override {
    return {CONFIG_SOC_SRAM_BASE, CONFIG_SOC_SRAM_SIZE};
  }
  word_t read(paddr_t addr, int len) override;
  void write(paddr_t addr, int len, word_t data) override;
  void bind(uint8_t *verilator_ptr);
};

class MromDevice : public MemoryDevice {
  uint8_t storage_[CONFIG_SOC_MROM_SIZE] PG_ALIGN {};
  size_t loaded_size_ = 0;

public:
  std::string_view name() const override { return "mrom"; }
  AddressRange range() const override {
    return {CONFIG_SOC_MROM_BASE, CONFIG_SOC_MROM_SIZE};
  }
  word_t read(paddr_t addr, int len) override;
  void write(paddr_t addr, int len, word_t data) override;
  long load_image(const char *path);
  void read_word(int addr, int *out);
  uint8_t *data() { return storage_; }
};

// Global device instances
extern FlashDevice g_flash;
extern PsramDevice g_psram;
extern SdramDevice g_sdram;
extern SramDevice g_sram;
extern MromDevice g_mrom;
extern AddressSpace g_address_space;

} // namespace npc::mem

#endif // NPC_MEMORY_H_
