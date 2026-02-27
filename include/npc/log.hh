#ifndef NPC_LOG_HH_
#define NPC_LOG_HH_

#include <npc/common.hh>

namespace npc {

class Logger {
  FILE *fp_ = stdout;

public:
  void init(const char *filename);
  void close();

  FILE *fp() const { return fp_; }
  bool enabled() const;

  template <typename... Args>
  void write(const char *fmt, Args... args) {
    if (enabled() && fp_) {
      std::fprintf(fp_, fmt, args...);
      std::fflush(fp_);
    }
  }
};

extern Logger g_logger;

class Timer {
  uint64_t boot_time_ = 0;

  static uint64_t now_us();

public:
  uint64_t elapsed_us();
  void seed_rand();
};

extern Timer g_timer;

} // namespace npc

#endif // NPC_LOG_HH_
