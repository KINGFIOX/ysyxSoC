#include "sdb/scoreboard.h"

#include "absl/log/log.h"
#include "absl/strings/str_format.h"
#include "cpu/abstract_cpu.h"

namespace npc {

static constexpr const char* kLoadMnemonics[] = {"lb",  "lh",  "lw", "ld",
                                                 "lbu", "lhu", "lwu"};
static constexpr const char* kStoreMnemonics[] = {"sb", "sh", "sw", "sd"};

static bool is_load(const std::string& mn) {
  for (const char* m : kLoadMnemonics)
    if (mn == m) return true;
  return false;
}

static bool is_store(const std::string& mn) {
  for (const char* m : kStoreMnemonics)
    if (mn == m) return true;
  return false;
}

static int rd(uint32_t inst) { return (inst >> 7) & 0x1f; }
static int rs1(uint32_t inst) { return (inst >> 15) & 0x1f; }
static int rs2(uint32_t inst) { return (inst >> 20) & 0x1f; }

// Returns true if `inst` is a CSRR* reading a hart-local counter CSR whose
// value can legitimately differ between the DUT and Spike (e.g. time/cycle
// /instret and their high halves).  For these reads we mirror the DUT's
// result into the golden register file instead of checking it.
static bool is_counter_csr_read(uint32_t inst, uint16_t* csr_out) {
  // SYSTEM opcode = 0b1110011
  if ((inst & 0x7f) != 0x73) return false;
  uint32_t funct3 = (inst >> 12) & 0x7;
  // CSRRW/RS/RC and their immediate variants (funct3 1..3, 5..7; not 0/4)
  if (funct3 == 0 || funct3 == 4) return false;
  uint16_t csr = static_cast<uint16_t>((inst >> 20) & 0xfff);
  switch (csr) {
    case 0xC00: // cycle
    case 0xC01: // time
    case 0xC02: // instret
    case 0xC80: // cycleh
    case 0xC81: // timeh
    case 0xC82: // instreth
    case 0xB00: // mcycle
    case 0xB02: // minstret
    case 0xB80: // mcycleh
    case 0xB82: // minstreth
      if (csr_out) *csr_out = csr;
      return true;
    default:
      return false;
  }
}

static int32_t imm_i(uint32_t inst) { return static_cast<int32_t>(inst) >> 20; }

static int32_t imm_s(uint32_t inst) {
  uint32_t hi = (inst >> 25) & 0x7f;
  uint32_t lo = (inst >> 7) & 0x1f;
  uint32_t raw = (hi << 5) | lo;
  return (static_cast<int32_t>(raw) << 20) >> 20;
}

static uint8_t mem_width(const std::string& mnemonic) {
  if (mnemonic == "lb" || mnemonic == "lbu" || mnemonic == "sb") return 1;
  if (mnemonic == "lh" || mnemonic == "lhu" || mnemonic == "sh") return 2;
  if (mnemonic == "lw" || mnemonic == "lwu" || mnemonic == "sw") return 4;
  if (mnemonic == "ld" || mnemonic == "sd") return 8;
  return 0;
}

#ifdef NPC_FTRACE
ScoreBoard::ScoreBoard(absl::Span<const uint8_t> flash_data,
                       std::unique_ptr<FuncTracer> ftrace)
    : golden_(flash_data),
#ifdef NPC_ITRACE
      itrace_(kTraceCapacity),
#endif
#ifdef NPC_DTRACE
      dtrace_(kTraceCapacity),
#endif
#ifdef NPC_MTRACE
      mtrace_(kTraceCapacity),
#endif
      ftrace_(std::move(ftrace)) {}
#else
ScoreBoard::ScoreBoard(absl::Span<const uint8_t> flash_data)
    : golden_(flash_data)
#ifdef NPC_ITRACE
      , itrace_(kTraceCapacity)
#endif
#ifdef NPC_DTRACE
      , dtrace_(kTraceCapacity)
#endif
#ifdef NPC_MTRACE
      , mtrace_(kTraceCapacity)
#endif
      {}
#endif

ScoreBoard::~ScoreBoard() = default;

StepResult ScoreBoard::scoreboard(const VerilatorCpu& dut,
                                  uint64_t* ebreak_a0) {
  uint64_t pc = dut.pc();
  uint32_t inst = dut.inst();
  auto [mnemonic, disasm_str] = golden_.disasm(inst);

#ifdef NPC_ITRACE
  itrace_.push(ITraceEntry(pc, inst, disasm_str));
#endif

  if (mnemonic == "ebreak") {
    auto a0 = dut.gpr(10);
    if (ebreak_a0 != nullptr) *ebreak_a0 = a0.value_or(0);
    return StepResult::kEBreak;
  }

#ifndef NPC_NODIFF
  if (dut.is_mmio()) {
    handle_mmio(dut, pc, inst, mnemonic, disasm_str);
  } else {
    (void)golden_.step();
    // Counter CSRs (time/cycle/instret/...) tick at different rates in the
    // DUT vs Spike.  Keep the golden register file in sync with the DUT so
    // downstream difftest on later instructions remains meaningful.
    uint16_t counter_csr = 0;
    if (is_counter_csr_read(inst, &counter_csr)) {
      int rd_idx = rd(inst);
      if (rd_idx != 0) {
        uint64_t data = dut.gpr(rd_idx).value_or(0);
        (void)golden_.set_gpr(rd_idx, data);
      }
    }
    if (is_store(mnemonic)) {
#ifdef NPC_MTRACE
      uint64_t base_val = golden_.gpr(rs1(inst)).value_or(0);
      uint64_t addr =
          static_cast<uint64_t>(static_cast<int64_t>(base_val) + imm_s(inst));
      uint64_t data = golden_.gpr(rs2(inst)).value_or(0);
      uint8_t w = mem_width(mnemonic);
      mtrace_.push(MTraceEntry(pc, MemDir::kWrite, addr, data, w, disasm_str));
#endif
    } else if (is_load(mnemonic)) {
      uint64_t base_val = golden_.gpr(rs1(inst)).value_or(0);
      uint64_t addr =
          static_cast<uint64_t>(static_cast<int64_t>(base_val) + imm_i(inst));
      uint8_t w = mem_width(mnemonic);
      [[maybe_unused]] uint64_t data = dut.mem_load(addr, w).value_or(0);
#ifdef NPC_MTRACE
      mtrace_.push(MTraceEntry(pc, MemDir::kRead, addr, data, w, disasm_str));
#endif
#ifdef NPC_FTRACE
    } else if ((mnemonic == "jal" || mnemonic == "jalr") && rd(inst) == 1) {
      ftrace_->push_call(pc, dut.dnpc(), disasm_str);
    } else if (mnemonic == "ret") {
      ftrace_->push_ret(pc, dut.dnpc(), disasm_str);
#endif
    }
  }

  if (!check_regs(dut)) {
    return StepResult::kDifftestFail;
  }
#else
#ifdef NPC_FTRACE
  if ((mnemonic == "jal" || mnemonic == "jalr") && rd(inst) == 1) {
    ftrace_->push_call(pc, dut.dnpc(), disasm_str);
  } else if (mnemonic == "ret") {
    ftrace_->push_ret(pc, dut.dnpc(), disasm_str);
  }
#endif
#endif

  return StepResult::kContinue;
}

void ScoreBoard::handle_mmio(const VerilatorCpu& dut, uint64_t pc,
                             uint32_t inst, const std::string& mnemonic,
                             const std::string& disasm_str) {
  if (is_load(mnemonic)) {
    uint64_t base_val = golden_.gpr(rs1(inst)).value_or(0);
    [[maybe_unused]] uint64_t addr =
        static_cast<uint64_t>(static_cast<int64_t>(base_val) + imm_i(inst));
    int rd_idx = rd(inst);
    uint64_t data = dut.gpr(rd_idx).value_or(0);
    if (rd_idx != 0) {
      (void)golden_.set_gpr(rd_idx, data);
    }
#ifdef NPC_DTRACE
    dtrace_.push(DTraceEntry(pc, MemDir::kRead, addr, data, mem_width(mnemonic),
                             disasm_str));
#endif
  } else if (is_store(mnemonic)) {
    uint64_t base_val = golden_.gpr(rs1(inst)).value_or(0);
    [[maybe_unused]] uint64_t addr =
        static_cast<uint64_t>(static_cast<int64_t>(base_val) + imm_s(inst));
    [[maybe_unused]] uint64_t data = golden_.gpr(rs2(inst)).value_or(0);
#ifdef NPC_DTRACE
    dtrace_.push(DTraceEntry(pc, MemDir::kWrite, addr, data,
                             mem_width(mnemonic), disasm_str));
#endif
  } else {
    LOG(WARNING) << absl::StreamFormat(
        "is_mmio=true but inst is not load/store, stepping golden: "
        "pc=0x%016x %s",
        dut.pc(), disasm_str);
    (void)golden_.step();
  }
  (void)golden_.set_pc(dut.dnpc());
}

bool ScoreBoard::check_regs(const VerilatorCpu& dut) const {
  uint64_t dut_pc = dut.dnpc();
  uint64_t ref_pc = golden_.pc();
  if (dut_pc != ref_pc) {
    LOG(ERROR) << absl::StreamFormat(
        "difftest FAIL: pc  dut=0x%016x  ref=0x%016x", dut_pc, ref_pc);
    return false;
  }

  for (int i = 1; i < 32; ++i) {
    uint64_t dut_val = dut.gpr(i).value_or(0);
    uint64_t ref_val = golden_.gpr(i).value_or(0);
    if (dut_val != ref_val) {
      LOG(ERROR) << absl::StreamFormat(
          "difftest FAIL: %s (x%d)  dut=0x%016x  ref=0x%016x", kGprNames[i], i,
          dut_val, ref_val);
      return false;
    }
  }

  struct CsrCheck {
    const char* name;
    uint64_t (AbstractCpu::*getter)() const;
  };
  static constexpr CsrCheck kCsrChecks[] = {
      {"mtvec", &AbstractCpu::mtvec},
      {"mepc", &AbstractCpu::mepc},
      {"mcause", &AbstractCpu::mcause},
      {"mtval", &AbstractCpu::mtval},
  };
  for (const auto& [name, getter] : kCsrChecks) {
    uint64_t dut_val = (dut.*getter)();
    uint64_t ref_val = (golden_.*getter)();
    if (dut_val != ref_val) {
      LOG(ERROR) << absl::StreamFormat(
          "difftest FAIL: %s  dut=0x%016x  ref=0x%016x", name, dut_val,
          ref_val);
      return false;
    }
  }
  return true;
}

bool ScoreBoard::check_store_mem(const VerilatorCpu& dut, uint32_t inst,
                                 const std::string& mnemonic) const {
  uint64_t base_val = golden_.gpr(rs1(inst)).value_or(0);
  uint64_t addr =
      static_cast<uint64_t>(static_cast<int64_t>(base_val) + imm_s(inst));
  uint8_t w = mem_width(mnemonic);
  if (w == 0) return true;

  uint64_t dut_val = dut.mem_load(addr, w).value_or(0);
  uint64_t ref_val = golden_.mem_load(addr, w).value_or(0);
  if (dut_val != ref_val) {
    LOG(ERROR) << absl::StreamFormat(
        "difftest FAIL: mem[0x%016x] (w%d)  dut=0x%016x  ref=0x%016x", addr, w,
        dut_val, ref_val);
    return false;
  }
  return true;
}

void ScoreBoard::dump_traces(const VerilatorCpu& dut) const {
#ifdef NPC_ITRACE
  LOG(WARNING) << absl::StreamFormat(
      "===== ITrace (recent %d instructions) =====", kTraceCapacity);
  LOG(WARNING) << itrace_.dump();
#endif

#ifdef NPC_DTRACE
  LOG(WARNING) << absl::StreamFormat(
      "===== DTrace (recent %d MMIO accesses) =====", kTraceCapacity);
  LOG(WARNING) << dtrace_.dump();
#endif

#ifdef NPC_MTRACE
  LOG(WARNING) << absl::StreamFormat(
      "===== MTrace (recent %d memory accesses) =====", kTraceCapacity);
  LOG(WARNING) << mtrace_.dump();
#endif

#ifdef NPC_FTRACE
  LOG(WARNING) << "===== FTrace (recent calls) =====";
  LOG(WARNING) << ftrace_->ring_buf.dump();
#endif

  LOG(WARNING) << "===== Register State =====";
  LOG(WARNING) << absl::StreamFormat("       %18s  %18s", "DUT", "REF");
  {
    uint64_t dut_dnpc = dut.dnpc();
    uint64_t ref_pc = golden_.pc();
    LOG(WARNING) << absl::StreamFormat(
        "pc     0x%016x  0x%016x%s", dut_dnpc, ref_pc,
        dut_dnpc != ref_pc ? "  <--- MISMATCH" : "");
  }
  for (int i = 1; i < 32; ++i) {
    uint64_t d = dut.gpr(i).value_or(0);
    uint64_t r = golden_.gpr(i).value_or(0);
    const char* mark = (d != r) ? "  <--- MISMATCH" : "";
    LOG(WARNING) << absl::StreamFormat("%-4s   0x%016x  0x%016x%s",
                                       kGprNames[i], d, r, mark);
  }

  LOG(WARNING) << "===== CSR State =====";
  LOG(WARNING) << absl::StreamFormat("       %18s  %18s", "DUT", "REF");
  LOG(WARNING) << absl::StreamFormat("mtvec  0x%016x  0x%016x", dut.mtvec(),
                                     golden_.mtvec());
  LOG(WARNING) << absl::StreamFormat("mepc   0x%016x  0x%016x", dut.mepc(),
                                     golden_.mepc());
  LOG(WARNING) << absl::StreamFormat("mcause 0x%016x  0x%016x", dut.mcause(),
                                     golden_.mcause());
  LOG(WARNING) << absl::StreamFormat("mtval  0x%016x  0x%016x", dut.mtval(),
                                     golden_.mtval());
}

}  // namespace npc
