#ifndef NPC_SDB_SCOREBOARD_H_
#define NPC_SDB_SCOREBOARD_H_

#include <cstdint>
#include <memory>
#include <string>
#include <utility>

#include "cpu/spike_cpu.h"
#include "cpu/verilator_cpu.h"
#include "tracer/dtrace.h"
#include "tracer/ftrace.h"
#include "tracer/itrace.h"
#include "tracer/mtrace.h"
#include "tracer/ring_buf.h"

namespace npc {

inline constexpr size_t kTraceCapacity = 16;

enum class StepResult {
  kContinue,
  kEBreak,
  kDifftestFail,
};

class ScoreBoard {
 public:
  ScoreBoard(absl::Span<const uint8_t> flash_data,
             std::unique_ptr<FuncTracer> ftrace);
  ~ScoreBoard();

  ScoreBoard(const ScoreBoard&) = delete;
  ScoreBoard& operator=(const ScoreBoard&) = delete;

  // Returns the step result and (for EBreak) the a0 value via out param.
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
  RingBuf<ITraceEntry> itrace_;
  RingBuf<DTraceEntry> dtrace_;
  RingBuf<MTraceEntry> mtrace_;
  std::unique_ptr<FuncTracer> ftrace_;
};

}  // namespace npc

#endif  // NPC_SDB_SCOREBOARD_H_
