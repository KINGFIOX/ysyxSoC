#ifndef NPC_TRACER_RING_BUF_H_
#define NPC_TRACER_RING_BUF_H_

#include <cstddef>
#include <sstream>
#include <string>
#include <vector>

namespace npc {

template <typename T>
class RingBuf {
 public:
  explicit RingBuf(size_t capacity) : entries_(capacity), capacity_(capacity) {}

  void push(T entry) {
    entries_[write_pos_ % capacity_] = std::move(entry);
    ++write_pos_;
  }

  std::string dump() const {
    std::ostringstream oss;
    size_t start = (write_pos_ > capacity_) ? write_pos_ - capacity_ : 0;
    for (size_t i = start; i < write_pos_; ++i) {
      const auto& entry = entries_[i % capacity_];
      oss << entry << "\n";
    }
    return oss.str();
  }

  size_t size() const { return std::min(write_pos_, capacity_); }

  bool empty() const { return write_pos_ == 0; }

  // Returns a reference to the most recently pushed entry.  UB if empty().
  const T& back() const {
    return entries_[(write_pos_ - 1) % capacity_];
  }

 private:
  std::vector<T> entries_;
  size_t capacity_;
  size_t write_pos_ = 0;
};

}  // namespace npc

#endif  // NPC_TRACER_RING_BUF_H_
