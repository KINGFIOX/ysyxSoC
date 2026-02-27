#include <npc/log.hh>
#include <ctime>
#include <cstdlib>

static_assert(CLOCKS_PER_SEC == 1000000, "CLOCKS_PER_SEC != 1000000");
static_assert(sizeof(clock_t) == 8, "sizeof(clock_t) != 8");

namespace npc {

uint64_t Timer::now_us() {
  struct timespec now;
  clock_gettime(CLOCK_MONOTONIC_COARSE, &now);
  return static_cast<uint64_t>(now.tv_sec) * 1'000'000 +
         static_cast<uint64_t>(now.tv_nsec) / 1'000;
}

uint64_t Timer::elapsed_us() {
  if (boot_time_ == 0) boot_time_ = now_us();
  return now_us() - boot_time_;
}

void Timer::seed_rand() {
  std::srand(static_cast<unsigned>(now_us()));
}

} // namespace npc

uint64_t get_time() {
  return npc::g_timer.elapsed_us();
}

void init_rand() {
  npc::g_timer.seed_rand();
}
