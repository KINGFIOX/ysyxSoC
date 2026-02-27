#ifndef NPC_RING_BUFFER_H_
#define NPC_RING_BUFFER_H_

#include <array>
#include <cstddef>

namespace npc {

template <typename T, size_t Capacity>
class RingBuffer {
  static_assert(Capacity > 0, "RingBuffer capacity must be > 0");

  std::array<T, Capacity> items_{};
  size_t ptr_   = 0;
  size_t count_ = 0;

public:
  void push(const T& item) {
    items_[ptr_] = item;
    if (count_ < Capacity) ++count_;
    ptr_ = (ptr_ + 1) % Capacity;
  }

  bool   empty() const { return count_ == 0; }
  size_t size()  const { return count_; }
  static constexpr size_t capacity() { return Capacity; }

  class Iterator {
    const RingBuffer* rb_;
    size_t idx_;
  public:
    Iterator(const RingBuffer* rb, size_t idx) : rb_(rb), idx_(idx) {}
    const T& operator*()  const { return rb_->at(idx_); }
    const T* operator->() const { return &rb_->at(idx_); }
    Iterator& operator++() { ++idx_; return *this; }
    bool operator!=(const Iterator& o) const { return idx_ != o.idx_; }
    bool operator==(const Iterator& o) const { return idx_ == o.idx_; }
    size_t index() const { return idx_; }
  };

  Iterator begin() const { return {this, 0}; }
  Iterator end()   const { return {this, count_}; }

  const T& at(size_t idx) const {
    size_t start = (ptr_ + Capacity - count_) % Capacity;
    return items_[(start + idx) % Capacity];
  }

  const T& back() const { return items_[(ptr_ + Capacity - 1) % Capacity]; }

  bool is_last(size_t idx) const { return idx == count_ - 1; }

  template <typename Fn>
  void foreach(Fn&& fn) const {
    size_t start = (ptr_ + Capacity - count_) % Capacity;
    for (size_t i = 0; i < count_; ++i) {
      size_t pos = (start + i) % Capacity;
      fn(i, items_[pos]);
    }
  }
};

} // namespace npc

#endif // NPC_RING_BUFFER_H_
