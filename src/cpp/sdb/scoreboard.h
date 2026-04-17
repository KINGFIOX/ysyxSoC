#ifndef NPC_SDB_SCOREBOARD_H_
#define NPC_SDB_SCOREBOARD_H_

#include <cstdint>
#include <string>
#ifdef NPC_FTRACE
#include <memory>
#endif

#include "cpu/spike_cpu.h"
#include "cpu/verilator_cpu.h"
#ifdef NPC_ITRACE
#include "tracer/itrace.h"
#endif
#ifdef NPC_DTRACE
#include "tracer/dtrace.h"
#endif
#ifdef NPC_MTRACE
#include "tracer/mtrace.h"
#endif
#ifdef NPC_FTRACE
#include "tracer/ftrace.h"
#endif
#if defined(NPC_ITRACE) || defined(NPC_DTRACE) || defined(NPC_MTRACE)
#include "tracer/ring_buf.h"
#endif

namespace npc {

inline constexpr size_t kTraceCapacity = 16;

enum class StepResult {
  kContinue,
  kEBreak,
  kDifftestFail,
};

class ScoreBoard {
 public:
#ifdef NPC_FTRACE
  ScoreBoard(absl::Span<const uint8_t> flash_data,
             std::unique_ptr<FuncTracer> ftrace);
#else
  explicit ScoreBoard(absl::Span<const uint8_t> flash_data);
#endif
  ~ScoreBoard();

  ScoreBoard(const ScoreBoard&) = delete;
  ScoreBoard& operator=(const ScoreBoard&) = delete;

  StepResult scoreboard(const VerilatorCpu& dut, uint64_t* ebreak_a0 = nullptr);
  void dump_traces(const VerilatorCpu& dut) const;

  SpikeCpu& golden() { return golden_; }

 private:
  void handle_mmio(const VerilatorCpu& dut, uint64_t pc, uint32_t inst,
                   const std::string& mnemonic, const std::string& disasm_str);
  bool check_regs(const VerilatorCpu& dut) const;
  bool check_store_mem(const VerilatorCpu& dut, uint32_t inst,
                       const std::string& mnemonic) const;

  SpikeCpu golden_;
#ifdef NPC_ITRACE
  RingBuf<ITraceEntry> itrace_;
#endif
#ifdef NPC_DTRACE
  RingBuf<DTraceEntry> dtrace_;
#endif
#ifdef NPC_MTRACE
  RingBuf<MTraceEntry> mtrace_;
#endif
#ifdef NPC_FTRACE
  std::unique_ptr<FuncTracer> ftrace_;
#endif
};

}  // namespace npc

#endif  // NPC_SDB_SCOREBOARD_H_
