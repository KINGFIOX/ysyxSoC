#include "dpi/memory.h"

#include <cstring>

#include "absl/strings/str_format.h"

namespace npc {

Memory::Memory(absl::Span<const uint8_t> flash_data)
    : flash_(kFlashSize, 0),
      sdram_(kSdramSize, 0) {
  std::memcpy(flash_.data(), flash_data.data(),
              std::min(flash_data.size(), flash_.size()));
}

absl::StatusOr<uint8_t> Memory::load_u8(uint64_t addr) const {
  if (uint64_t off = addr - kFlashBase; off < kFlashSize) {
    return flash_[off];
  }
  if (uint64_t off = addr - kSdramBase; off < kSdramSize) {
    return sdram_[off];
  }
  return absl::OutOfRangeError(
      absl::StrFormat("address 0x%x is out of range", addr));
}

absl::Status Memory::store_u8(uint64_t addr, uint8_t val) {
  if (uint64_t off = addr - kSdramBase; off < kSdramSize) {
    sdram_[off] = val;
    return absl::OkStatus();
  }
  if (uint64_t off = addr - kFlashBase; off < kFlashSize) {
    (void)off;
    return absl::FailedPreconditionError("write to read-only flash");
  }
  return absl::OutOfRangeError(
      absl::StrFormat("address 0x%x is out of range", addr));
}

absl::StatusOr<uint64_t> Memory::load(uint64_t addr, uint8_t width) const {
  uint64_t result = 0;
  for (uint8_t i = 0; i < width; ++i) {
    auto byte = load_u8(addr + i);
    if (!byte.ok()) return byte.status();
    result |= static_cast<uint64_t>(*byte) << (i * 8);
  }
  return result;
}

absl::Status Memory::store(uint64_t addr, uint64_t value, uint8_t width) {
  for (uint8_t i = 0; i < width; ++i) {
    auto s = store_u8(addr + i, static_cast<uint8_t>(value >> (i * 8)));
    if (!s.ok()) return s;
  }
  return absl::OkStatus();
}

}  // namespace npc
