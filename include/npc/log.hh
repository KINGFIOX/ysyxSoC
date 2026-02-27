#ifndef NPC_LOG_HH_
#define NPC_LOG_HH_

#include <format>
#include <npc/common.hh>
#include <string>
#include <string_view>

namespace npc {

class Logger {
  FILE *fp_ = stdout;

public:
  void init(const char *filename);
  void close();

  FILE *fp() const { return fp_; }
  bool enabled() const;

  void write(std::string_view msg);

  template <typename... Args>
  void write(std::format_string<Args...> fmt, Args &&...args) {
    write(std::format(fmt, std::forward<Args>(args)...));
  }
};

extern Logger g_logger;

// Format and output to stdout and log file. Used by Log macro.
template <typename... Args>
inline void log_impl(std::format_string<Args...> fmt, Args &&...args) {
  std::string msg = std::format(fmt, std::forward<Args>(args)...);
  std::printf("%s", msg.c_str());
  g_logger.write(msg);
}

// Raw log without prefix. Used by trace dump and similar.
template <typename... Args>
inline void log_raw(std::format_string<Args...> fmt, Args &&...args) {
  log_impl(fmt, std::forward<Args>(args)...);
}

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
