#include <npc/log.hh>
#include <cassert>

extern uint64_t g_nr_guest_inst;

namespace npc {

Logger g_logger;
Timer  g_timer;

void Logger::init(const char *filename) {
  fp_ = stdout;
  if (filename) {
    FILE *f = std::fopen(filename, "w");
    assert(f && "Cannot open log file");
    fp_ = f;
  }
}

void Logger::close() {
  if (fp_ && fp_ != stdout && fp_ != stderr) {
    std::fclose(fp_);
    fp_ = nullptr;
  }
}

bool Logger::enabled() const {
#ifdef CONFIG_TRACE
  const uint64_t start = static_cast<uint64_t>(CONFIG_TRACE_START);
  const uint64_t end = static_cast<uint64_t>(CONFIG_TRACE_END);
  return (g_nr_guest_inst >= start) && (g_nr_guest_inst <= end) &&
         MUXDEF(CONFIG_ITRACE, fp_ != nullptr, true);
#else
  return false;
#endif
}

void Logger::write(std::string_view msg) {
#ifdef CONFIG_TARGET_NATIVE_ELF
  if (enabled() && fp_ != nullptr) {
    std::fprintf(fp_, "%.*s", static_cast<int>(msg.size()), msg.data());
    std::fflush(fp_);
  }
#endif
}

} // namespace npc

void init_log(const char *log_file) {
  npc::g_logger.init(log_file);
  Log("Log is written to {}", log_file ? log_file : "stdout");
}

bool log_enable() {
  return npc::g_logger.enabled();
}
