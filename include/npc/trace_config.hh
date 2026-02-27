#ifndef NPC_TRACE_CONFIG_H_
#define NPC_TRACE_CONFIG_H_

#include <cstddef>
#include <generated/autoconf.h>

namespace npc::trace {

struct Config {
  static constexpr bool itrace =
#ifdef CONFIG_ITRACE
      true;
#else
      false;
#endif

  static constexpr bool ftrace =
#ifdef CONFIG_FTRACE
      true;
#else
      false;
#endif

  static constexpr bool mtrace =
#ifdef CONFIG_MTRACE
      true;
#else
      false;
#endif

  static constexpr bool etrace =
#ifdef CONFIG_ETRACE
      true;
#else
      false;
#endif

  static constexpr bool verilator_trace =
#ifdef CONFIG_VERILATOR_TRACE
      true;
#else
      false;
#endif

  static constexpr bool difftest =
#ifdef CONFIG_DIFFTEST
      true;
#else
      false;
#endif

  static constexpr bool watchpoint =
#ifdef CONFIG_WATCHPOINT
      true;
#else
      false;
#endif

  static constexpr size_t iringbuf_size =
#ifdef CONFIG_IRINGBUF_SIZE
      CONFIG_IRINGBUF_SIZE;
#else
      16;
#endif

  static constexpr size_t etrace_buf_size =
#ifdef CONFIG_ETRACE_BUF_SIZE
      CONFIG_ETRACE_BUF_SIZE;
#else
      16;
#endif

  static constexpr size_t ftrace_stack_max =
#ifdef CONFIG_FTRACE_STACK_MAX
      CONFIG_FTRACE_STACK_MAX;
#else
      64;
#endif

  static constexpr size_t ftrace_log_size =
#ifdef CONFIG_FTRACE_LOG_SIZE
      CONFIG_FTRACE_LOG_SIZE;
#else
      1024;
#endif

  static constexpr size_t mtrace_buf_size = 16;
};

} // namespace npc::trace

#endif // NPC_TRACE_CONFIG_H_
