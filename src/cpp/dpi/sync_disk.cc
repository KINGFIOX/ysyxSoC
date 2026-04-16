#include "dpi/sync_disk.h"

#include <cerrno>
#include <cstring>
#include <stdexcept>
#include <vector>

#include <fcntl.h>
#include <unistd.h>

#include "absl/log/log.h"
#include "dpi/memory.h"

namespace npc {

static constexpr uint32_t kSectorSize = 512;

SyncDisk::SyncDisk(const std::string& image_path)
    : fd_(-1),
      cmd_(kCmdNone),
      status_(kStatusIdle),
      count_(0),
      reserved_(0),
      lba_low_(0),
      lba_high_(0),
      guest_pa_low_(0),
      guest_pa_high_(0),
      error_code_(0),
      last_result_bytes_(0) {
  fd_ = open(image_path.c_str(), O_RDWR, 0);
  if (fd_ < 0) {
    throw std::runtime_error("failed to open fs image '" + image_path +
                             "': " + strerror(errno));
  }
}

SyncDisk::~SyncDisk() {
  if (fd_ >= 0) {
    close(fd_);
    fd_ = -1;
  }
}

uint64_t SyncDisk::lba() const {
  return (uint64_t{lba_high_} << 32) | lba_low_;
}

uint64_t SyncDisk::guest_pa() const {
  return (uint64_t{guest_pa_high_} << 32) | guest_pa_low_;
}

int32_t SyncDisk::load(uint32_t offset) {
  switch (offset & ~uint32_t{0x3}) {
    case 0x00: return static_cast<int32_t>(cmd_);
    case 0x04: return static_cast<int32_t>(status_);
    case 0x08: return static_cast<int32_t>(count_);
    case 0x0c: return static_cast<int32_t>(reserved_);
    case 0x10: return static_cast<int32_t>(lba_low_);
    case 0x14: return static_cast<int32_t>(lba_high_);
    case 0x18: return static_cast<int32_t>(guest_pa_low_);
    case 0x1c: return static_cast<int32_t>(guest_pa_high_);
    case 0x20: return static_cast<int32_t>(error_code_);
    case 0x24: return static_cast<int32_t>(last_result_bytes_);
    default: return 0;
  }
}

void SyncDisk::store(uint32_t offset, int32_t value) {
  auto val = static_cast<uint32_t>(value);
  switch (offset & ~uint32_t{0x3}) {
    case 0x00:
      cmd_ = val;
      if (cmd_ == kCmdNone) {
        status_ = kStatusIdle;
        error_code_ = 0;
      } else {
        execute_command();
      }
      break;
    case 0x04:
      status_ = val;
      if (status_ == kStatusIdle) error_code_ = 0;
      break;
    case 0x08: count_ = val; break;
    case 0x0c: reserved_ = val; break;
    case 0x10: lba_low_ = val; break;
    case 0x14: lba_high_ = val; break;
    case 0x18: guest_pa_low_ = val; break;
    case 0x1c: guest_pa_high_ = val; break;
    default: break;
  }
}

void SyncDisk::execute_command() {
  if (count_ == 0) {
    status_ = kStatusDone;
    error_code_ = 0;
    last_result_bytes_ = 0;
    cmd_ = kCmdNone;
    return;
  }
  if (cmd_ != kCmdRead && cmd_ != kCmdWrite) {
    status_ = kStatusError;
    error_code_ = EINVAL;
    last_result_bytes_ = 0;
    cmd_ = kCmdNone;
    return;
  }

  auto* mem = g_memory;
  if (mem == nullptr) {
    status_ = kStatusError;
    error_code_ = EFAULT;
    last_result_bytes_ = 0;
    cmd_ = kCmdNone;
    return;
  }

  const uint64_t total_bytes = uint64_t{count_} * kSectorSize;
  const auto disk_offset = static_cast<off_t>(lba() * kSectorSize);
  const uint64_t pa = guest_pa();
  const uint64_t sdram_off = pa - kSdramBase;

  if (sdram_off + total_bytes > mem->sdram_size()) {
    LOG(ERROR) << "sync_disk: DMA address 0x" << std::hex << pa
               << " + 0x" << total_bytes << " out of SDRAM range";
    status_ = kStatusError;
    error_code_ = EFAULT;
    last_result_bytes_ = 0;
    cmd_ = kCmdNone;
    return;
  }

  uint8_t* sdram_ptr = mem->sdram_data() + sdram_off;

  if (cmd_ == kCmdRead) {
    ssize_t got = pread(fd_, sdram_ptr, total_bytes, disk_offset);
    if (got < 0) {
      status_ = kStatusError;
      error_code_ = errno;
      last_result_bytes_ = 0;
      cmd_ = kCmdNone;
      return;
    }
    if (static_cast<size_t>(got) < total_bytes) {
      std::memset(sdram_ptr + got, 0, total_bytes - got);
    }
    last_result_bytes_ = static_cast<uint32_t>(total_bytes);
  } else {
    ssize_t wrote = pwrite(fd_, sdram_ptr, total_bytes, disk_offset);
    if (wrote < 0 || static_cast<size_t>(wrote) != total_bytes) {
      status_ = kStatusError;
      error_code_ = (wrote < 0) ? errno : EIO;
      last_result_bytes_ = 0;
      cmd_ = kCmdNone;
      return;
    }
    last_result_bytes_ = static_cast<uint32_t>(total_bytes);
  }

  status_ = kStatusDone;
  error_code_ = 0;
  cmd_ = kCmdNone;
}

}  // namespace npc

// DPI-C callbacks called from Verilator RTL
extern "C" {

void sync_disk_load(int64_t offset, int32_t* data) {
  auto* disk = npc::g_sync_disk;
  if (disk == nullptr) {
    *data = 0;
    return;
  }
  *data = disk->load(static_cast<uint32_t>(offset));
}

void sync_disk_store(int64_t offset, int32_t data) {
  auto* disk = npc::g_sync_disk;
  if (disk == nullptr) return;
  disk->store(static_cast<uint32_t>(offset), data);
}

}  // extern "C"
