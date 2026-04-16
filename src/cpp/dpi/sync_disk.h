#ifndef NPC_DPI_SYNC_DISK_H_
#define NPC_DPI_SYNC_DISK_H_

#include <cstdint>
#include <string>

namespace npc {

class SyncDisk {
 public:
  explicit SyncDisk(const std::string& image_path);
  ~SyncDisk();

  SyncDisk(const SyncDisk&) = delete;
  SyncDisk& operator=(const SyncDisk&) = delete;

  int32_t load(uint32_t offset);
  void store(uint32_t offset, int32_t value);

 private:
  enum : uint32_t {
    kCmdNone = 0,
    kCmdRead = 1,
    kCmdWrite = 2,
  };

  enum : uint32_t {
    kStatusIdle = 0,
    kStatusDone = 1,
    kStatusError = 2,
  };

  void execute_command();
  uint64_t lba() const;
  uint64_t guest_pa() const;

  int fd_;
  uint32_t cmd_;
  uint32_t status_;
  uint32_t count_;
  uint32_t reserved_;
  uint32_t lba_low_;
  uint32_t lba_high_;
  uint32_t guest_pa_low_;
  uint32_t guest_pa_high_;
  uint32_t error_code_;
  uint32_t last_result_bytes_;
};

inline SyncDisk* g_sync_disk = nullptr;

}  // namespace npc

#endif  // NPC_DPI_SYNC_DISK_H_
