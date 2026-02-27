#include <npc/memory.hh>
#include <cstdio>
#include <cstdlib>
#include <cstring>

namespace npc::mem {

FlashDevice g_flash;

static constexpr uint32_t builtin_img[] = {
    0x00000297, // auipc t0,0
    0x00028823, // sb  zero,16(t0)
    0x0102c503, // lbu a0,16(t0)
    0x00100073, // ebreak (used as npc_trap)
    0x00000000,
};

FlashDevice::~FlashDevice() { std::free(mem_); }

long FlashDevice::load_image(const char *path) {
  auto r = range();
  Log("Flash area [" FMT_PADDR ", " FMT_PADDR "]", r.base,
      static_cast<paddr_t>(r.base + r.size - 1));

  if (!path) {
    Log("No image is given. Use the default built-in image.");
    loaded_size_ = sizeof(builtin_img);
    mem_ = static_cast<uint8_t *>(std::malloc(loaded_size_));
    std::memcpy(mem_, builtin_img, loaded_size_);
    return loaded_size_;
  }

  FILE *fp = std::fopen(path, "rb");
  Assert(fp, "Can not open '%s'", path);

  std::fseek(fp, 0, SEEK_END);
  long size = std::ftell(fp);
  Log("The image is %s, size = %ld", path, size);

  if (size > static_cast<long>(r.size)) {
    Log("Warning: image size %ld exceeds Flash size %zu, truncated", size,
        r.size);
    size = static_cast<long>(r.size);
  }

  mem_ = static_cast<uint8_t *>(std::malloc(size));
  std::fseek(fp, 0, SEEK_SET);
  int ret = std::fread(mem_, size, 1, fp);
  assert(ret == 1);
  std::fclose(fp);

  loaded_size_ = size;
  return size;
}

word_t FlashDevice::read(paddr_t addr, int len) {
  auto off = range().offset(addr);
  word_t result = 0;
  for (int i = 0; i < len; i++) {
    uint8_t byte = 0;
    if (mem_ && static_cast<long>(off + i) < loaded_size_)
      byte = mem_[off + i];
    result |= static_cast<word_t>(byte) << (i * 8);
  }
  return result;
}

void FlashDevice::write(paddr_t addr, int len, word_t data) {
  (void)len;
  (void)data;
  panic("Flash is read-only, cannot write to address " FMT_PADDR, addr);
}

void FlashDevice::read_byte(int offset, char *out) {
  if (!mem_ || offset < 0 ||
      offset >= static_cast<int>(CONFIG_SOC_XIP_FLASH_SIZE)) {
    *out = 0;
    return;
  }
  *out = (offset < loaded_size_) ? static_cast<char>(mem_[offset]) : 0;
}

} // namespace npc::mem

// ======================== DPI-C and C-compatible wrappers ========================

extern "C" void flash_read(int addr, char *data) {
  npc::mem::g_flash.read_byte(addr, data);
}

long init_flash(const char *img_file) {
  return npc::mem::g_flash.load_image(img_file);
}

bool in_flash(paddr_t addr) {
  return npc::mem::g_flash.range().contains(addr);
}

uint8_t *get_flash_ptr() { return npc::mem::g_flash.data(); }
