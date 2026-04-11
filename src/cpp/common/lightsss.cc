#include "common/lightsss.h"

#include <cassert>
#include <chrono>
#include <cstring>

#include <signal.h>
#include <sys/wait.h>
#include <unistd.h>

#include "absl/log/log.h"

namespace npc {

static constexpr uint64_t kForkIntervalMs = 3000;
static constexpr size_t kSlotSize = 2;

uint64_t LightSSS::now_ms() {
  using namespace std::chrono;
  return duration_cast<milliseconds>(
             steady_clock::now().time_since_epoch())
      .count();
}

LightSSS::LightSSS() {
  int fds[2];
  int ret = pipe(fds);
  assert(ret == 0);
  (void)ret;
  pipe_rfd_ = fds[0];
  pipe_wfd_ = fds[1];
  last_fork_ms_ = now_ms();
}

LightSSS::~LightSSS() {
  if (!is_child_process_) {
    do_clear();
  }
}

bool LightSSS::should_fork() const {
  return !is_child_process_ && (now_ms() - last_fork_ms_ >= kForkIntervalMs);
}

ForkResult LightSSS::do_fork() {
  if (pid_slots_.size() >= kSlotSize) {
    int oldest_pid = pid_slots_.back();
    pid_slots_.pop_back();
    kill(oldest_pid, SIGKILL);
    waitpid(oldest_pid, nullptr, 0);
  }

  pid_t pid = fork();
  assert(pid >= 0);

  if (pid > 0) {
    // Parent
    pid_slots_.push_front(pid);
    last_fork_ms_ = now_ms();
    LOG(INFO) << "[lightsss] forked checkpoint pid=" << pid
              << ", slots=" << pid_slots_.size();
    return ForkResult{false, 0};
  }

  // Child
  pid_slots_.clear();
  is_child_process_ = true;

  uint64_t end_cycles = 0;
  ssize_t n = read(pipe_rfd_, &end_cycles, sizeof(end_cycles));
  assert(n == sizeof(end_cycles));
  (void)n;

  pid_t my_pid = getpid();
  LOG(INFO) << "[lightsss] child pid=" << my_pid
            << " woke up, will dump wave until cycle " << end_cycles;
  return ForkResult{true, end_cycles};
}

void LightSSS::wakeup_child(uint64_t cycles) {
  if (pid_slots_.empty()) {
    LOG(WARNING) << "[lightsss] no checkpoint to wake up";
    return;
  }

  // pop back
  int oldest_pid = pid_slots_.back();
  pid_slots_.pop_back();

  for (int pid : pid_slots_) {
    kill(pid, SIGKILL);
    waitpid(pid, nullptr, 0);
  }

  ssize_t n = write(pipe_wfd_, &cycles, sizeof(cycles));
  assert(n == sizeof(cycles));
  (void)n;

  LOG(INFO) << "[lightsss] waking child pid=" << oldest_pid << ", waiting...";
  waitpid(oldest_pid, nullptr, 0);
  LOG(INFO) << "[lightsss] child finished";

  pid_slots_.clear();
}

void LightSSS::do_clear() {
  close(pipe_rfd_);
  close(pipe_wfd_);
  while (!pid_slots_.empty()) {
    int pid = pid_slots_.back();
    pid_slots_.pop_back();
    kill(pid, SIGKILL);
    waitpid(pid, nullptr, 0);
  }
}

}  // namespace npc
