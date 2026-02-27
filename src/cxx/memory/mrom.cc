#include <npc/memory.hh>
#include <cstdio>
#include <cstring>

namespace npc::mem {

MromDevice g_mrom;

static constexpr uint32_t builtin_img[] = {
    0x00000297, // auipc t0,0
    0x00028823, // sb  zero,16(t0)
    0x0102c503, // lbu a0,16(t0)
    0x00100073, // ebreak (used as npc_trap)
    0xdeadbeef,
};

long MromDevice::load_image(const char *path) {
  auto r = range();
  Log("mrom area [" FMT_PADDR ", " FMT_PADDR "]", r.base,
      static_cast<paddr_t>(r.base + r.size - 1));

  if (!path) {
    Log("No image is given. Use the default build-in image.");
    std::memcpy(storage_, builtin_img, sizeof(builtin_img));
    loaded_size_ = sizeof(builtin_img);
    return static_cast<long>(loaded_size_);
  }

  FILE *fp = std::fopen(path, "rb");
  Assert(fp, "Can not open '%s'", path);

  std::fseek(fp, 0, SEEK_END);
  long size = std::ftell(fp);
  Log("The image is %s, size = %ld", path, size);

  if (size > static_cast<long>(r.size)) {
    Log("Warning: image size %ld exceeds MROM size %zu, truncated", size,
        r.size);
    size = static_cast<long>(r.size);
  }

  std::fseek(fp, 0, SEEK_SET);
  int ret = std::fread(storage_, size, 1, fp);
  assert(ret == 1);
  std::fclose(fp);

  loaded_size_ = size;
  return size;
}

word_t MromDevice::read(paddr_t addr, int len) {
  auto off = range().offset(addr);
  word_t result = 0;
  for (int i = 0; i < len; i++) {
    uint8_t byte = (off + i < loaded_size_) ? storage_[off + i] : 0;
    result |= static_cast<word_t>(byte) << (i * 8);
  }
  return result;
}

void MromDevice::write(paddr_t addr, int len, word_t data) {
  (void)len;
  (void)data;
  panic("MROM is read-only, cannot write to address " FMT_PADDR, addr);
}

void MromDevice::read_word(int addr, int *out) {
  uint32_t offset = static_cast<uint32_t>(addr) - CONFIG_SOC_MROM_BASE;
  if (offset < loaded_size_ && offset + 4 <= CONFIG_SOC_MROM_SIZE) {
    *out = static_cast<int>(storage_[offset] |
                            (storage_[offset + 1] << 8) |
                            (storage_[offset + 2] << 16) |
                            (storage_[offset + 3] << 24));
  } else {
    *out = 0x00100073; // ebreak as default
  }
}

} // namespace npc::mem

// ======================== DPI-C wrappers ========================

extern "C" void mrom_read(int addr, int *data) {
  npc::mem::g_mrom.read_word(addr, data);
}

bool in_mrom(paddr_t addr) {
  return npc::mem::g_mrom.range().contains(addr);
}

uint8_t *get_mrom_ptr() { return npc::mem::g_mrom.data(); }
