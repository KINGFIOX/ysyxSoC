#ifndef NPC_DPI_MEMORY_H_
#define NPC_DPI_MEMORY_H_

#include <cstdint>
#include <vector>

#include "absl/status/status.h"
#include "absl/status/statusor.h"
#include "absl/types/span.h"

namespace npc {

inline constexpr uint64_t kFlashBase = 0x30000000;
inline constexpr uint64_t kFlashSize = 0x10000000;
inline constexpr uint64_t kSdramBase = 0x80000000;
inline constexpr uint64_t kSdramSize = 0x10000000;

class Memory {
 public:
  explicit Memory(absl::Span<const uint8_t> flash_data);

  uint8_t* flash_data() { return flash_.data(); }
  size_t flash_size() const { return flash_.size(); }
  uint8_t* sdram_data() { return sdram_.data(); }
  size_t sdram_size() const { return sdram_.size(); }

  void load_sdram(absl::Span<const uint8_t> data);

  absl::StatusOr<uint8_t> load_u8(uint64_t addr) const;
  absl::Status store_u8(uint64_t addr, uint8_t val);

  absl::StatusOr<uint64_t> load(uint64_t addr, uint8_t width) const;
  absl::Status store(uint64_t addr, uint64_t value, uint8_t width);

 private:
  std::vector<uint8_t> flash_;
  std::vector<uint8_t> sdram_;
};

// Global memory pointer, set by VerilatorCpu during construction.
// DPI callbacks use this to access simulation memory.
inline Memory* g_memory = nullptr;

}  // namespace npc

#endif  // NPC_DPI_MEMORY_H_
