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
  return (g_nr_guest_inst >= CONFIG_TRACE_START) &&
         (g_nr_guest_inst <= CONFIG_TRACE_END) &&
         MUXDEF(CONFIG_ITRACE, fp_ != nullptr, true);
#else
  return false;
#endif
}

} // namespace npc

FILE *log_fp = nullptr;

void init_log(const char *log_file) {
  npc::g_logger.init(log_file);
  log_fp = npc::g_logger.fp();
  Log("Log is written to %s", log_file ? log_file : "stdout");
}

bool log_enable() {
  return npc::g_logger.enabled();
}
