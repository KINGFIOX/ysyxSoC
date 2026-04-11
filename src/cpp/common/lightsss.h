#ifndef NPC_COMMON_LIGHTSSS_H_
#define NPC_COMMON_LIGHTSSS_H_

#include <cstdint>
#include <deque>

namespace npc {

struct ForkResult {
  bool is_child;
  uint64_t end_cycles;
};

// Fork-based checkpoint mechanism: periodically fork child processes that
// sleep until woken up to dump waveforms. When an error occurs in the parent,
// the most recent checkpoint child is woken up and replays with wave dumping.
class LightSSS {
 public:
  LightSSS();
  ~LightSSS();

  LightSSS(const LightSSS&) = delete;
  LightSSS& operator=(const LightSSS&) = delete;

  bool should_fork() const;
  bool is_child() const { return is_child_process_; }

  ForkResult do_fork();
  void wakeup_child(uint64_t cycles);
  void do_clear();

 private:
  int pipe_rfd_;
  int pipe_wfd_;
  std::deque<int> pid_slots_;
  bool is_child_process_ = false;
  uint64_t last_fork_ms_ = 0;

  static uint64_t now_ms();
};

}  // namespace npc

#endif  // NPC_COMMON_LIGHTSSS_H_
