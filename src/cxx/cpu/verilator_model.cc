#include <npc/cpu_model.hh>
#include <npc/cpu.hh>
#include <npc/isa.hh>

void init_sram(uint8_t *verilator_sram_ptr);

#include "VNPCSoC.h"
#include "VNPCSoC___024root.h"
#include <verilated.h>

#ifdef CONFIG_VERILATOR_TRACE
#include <verilated_vcd_c.h>
#endif

#define DEBUG_GPR(n) top_->debug_gpr_##n

namespace npc {

class VerilatorModel final : public CpuModel {
  VNPCSoC *top_ = nullptr;
  VerilatedContext *ctx_ = nullptr;
#ifdef CONFIG_VERILATOR_TRACE
  VerilatedVcdC *tfp_ = nullptr;
  uint64_t sim_time_ = 0;
#endif
  uint64_t ncycles_ = 0;
  CpuState state_{};
  int argc_ = 0;
  char **argv_ = nullptr;

  void tick() {
    top_->clock = 0;
    top_->eval();
#ifdef CONFIG_VERILATOR_TRACE
    tfp_->dump(sim_time_++);
#endif

    top_->clock = 1;
    top_->eval();
    ncycles_++;
#ifdef CONFIG_VERILATOR_TRACE
    tfp_->dump(sim_time_++);
#endif
  }

  void reset(int cycles = 15) {
    top_->reset = 1;
    for (int i = 0; i < cycles; i++) {
      tick();
    }
    top_->reset = 0;
  }

  void sync_from_verilator() {
    state_.pc = top_->debug_dnpc;

    state_.gpr[0]  = DEBUG_GPR(0);
    state_.gpr[1]  = DEBUG_GPR(1);
    state_.gpr[2]  = DEBUG_GPR(2);
    state_.gpr[3]  = DEBUG_GPR(3);
    state_.gpr[4]  = DEBUG_GPR(4);
    state_.gpr[5]  = DEBUG_GPR(5);   // NOLINT
    state_.gpr[6]  = DEBUG_GPR(6);   // NOLINT
    state_.gpr[7]  = DEBUG_GPR(7);   // NOLINT
    state_.gpr[8]  = DEBUG_GPR(8);   // NOLINT
    state_.gpr[9]  = DEBUG_GPR(9);   // NOLINT
    state_.gpr[10] = DEBUG_GPR(10);  // NOLINT
    state_.gpr[11] = DEBUG_GPR(11);  // NOLINT
    state_.gpr[12] = DEBUG_GPR(12);  // NOLINT
    state_.gpr[13] = DEBUG_GPR(13);  // NOLINT
    state_.gpr[14] = DEBUG_GPR(14);  // NOLINT
    state_.gpr[15] = DEBUG_GPR(15);  // NOLINT
    state_.gpr[16] = DEBUG_GPR(16);  // NOLINT
    state_.gpr[17] = DEBUG_GPR(17);  // NOLINT
    state_.gpr[18] = DEBUG_GPR(18);  // NOLINT
    state_.gpr[19] = DEBUG_GPR(19);  // NOLINT
    state_.gpr[20] = DEBUG_GPR(20);  // NOLINT
    state_.gpr[21] = DEBUG_GPR(21);  // NOLINT
    state_.gpr[22] = DEBUG_GPR(22);  // NOLINT
    state_.gpr[23] = DEBUG_GPR(23);  // NOLINT
    state_.gpr[24] = DEBUG_GPR(24);  // NOLINT
    state_.gpr[25] = DEBUG_GPR(25);  // NOLINT
    state_.gpr[26] = DEBUG_GPR(26);  // NOLINT
    state_.gpr[27] = DEBUG_GPR(27);  // NOLINT
    state_.gpr[28] = DEBUG_GPR(28);  // NOLINT
    state_.gpr[29] = DEBUG_GPR(29);  // NOLINT
    state_.gpr[30] = DEBUG_GPR(30);  // NOLINT
    state_.gpr[31] = DEBUG_GPR(31);  // NOLINT

    state_.mstatus   = top_->debug_csr_mstatus;
    state_.mtvec     = top_->debug_csr_mtvec;
    state_.mepc      = top_->debug_csr_mepc;
    state_.mcause    = top_->debug_csr_mcause;
    state_.mtval     = top_->debug_csr_mtval;
    state_.mvendorid = top_->debug_csr_mvendorid;
    state_.marchid   = top_->debug_csr_marchid;
  }

  void flush_vcd() {
#ifdef CONFIG_VERILATOR_TRACE
    if (tfp_) {
      tfp_->flush();
    }
#endif
  }

public:
  VerilatorModel(int argc, char **argv) : argc_(argc), argv_(argv) {}

  bool init() override {
    ctx_ = new VerilatedContext;
    ctx_->commandArgs(argc_, argv_);

    top_ = new VNPCSoC(ctx_);

#ifdef CONFIG_VERILATOR_TRACE
    Verilated::traceEverOn(true);
    tfp_ = new VerilatedVcdC;
    top_->trace(tfp_, 99);
    tfp_->open("build/npc_core.vcd");
    Log("VCD trace enabled: build/npc_core.vcd");
#endif

    reset();

#define VERILATOR_SRAM_MEMORY                                                  \
  top_->rootp                                                                  \
      ->NPCSoC__DOT__dut__DOT__asic__DOT__axi4ram__DOT__mem_ext__DOT__Memory

    init_sram(reinterpret_cast<uint8_t *>(VERILATOR_SRAM_MEMORY.data()));

    state_.pc = RESET_VECTOR;

    Log("Verilator core initialized, reset complete");
    return true;
  }

  void fini() override {
#ifdef CONFIG_VERILATOR_TRACE
    if (tfp_) {
      tfp_->flush();
      tfp_->close();
      delete tfp_;
      tfp_ = nullptr;
    }
#endif

    if (top_) {
      top_->final();
      delete top_;
      top_ = nullptr;
    }

    if (ctx_) {
      delete ctx_;
      ctx_ = nullptr;
    }
    Log("Verilator core finalized");
    Log("total cycles: {}", ncycles_);
  }

  StepResult step() override {
    top_->step = 1;

    const int MAX_CYCLES = 1000000;
    int cycles = 0;
    do {
      tick();
      cycles++;
      if (cycles >= MAX_CYCLES) {
        Log("Warning: step exceeded {} cycles without debug_commit",
            MAX_CYCLES);
        set_npc_state(NPC_ABORT, state_.pc, -1);
        return {state_.pc, state_.pc, 0, false};
      }
    } while (!top_->debug_valid);

    StepResult result{};
    result.pc = top_->debug_pc;
    result.inst = top_->debug_inst;
    result.is_mmio = top_->debug_isMMIO;

    sync_from_verilator();

    top_->step = 0;
    top_->eval();
    return result;
  }

  [[nodiscard]] word_t pc() const override { return state_.pc; }
  void set_pc(word_t v) override { state_.pc = v; }

  [[nodiscard]] word_t gpr(int i) const override { return state_.gpr.at(i); }
  void set_gpr(int i, word_t v) override { state_.gpr.at(i) = v; }

  [[nodiscard]] std::span<const word_t, num_gprs> gprs() const override {
    return state_.gpr;
  }
  std::span<word_t, num_gprs> gprs_mut() override {
    return state_.gpr;
  }

  [[nodiscard]] word_t mstatus() const override { return state_.mstatus; }
  void set_mstatus(word_t v) override { state_.mstatus = v; }
  [[nodiscard]] word_t mtvec() const override { return state_.mtvec; }
  void set_mtvec(word_t v) override { state_.mtvec = v; }
  [[nodiscard]] word_t mepc() const override { return state_.mepc; }
  void set_mepc(word_t v) override { state_.mepc = v; }
  [[nodiscard]] word_t mcause() const override { return state_.mcause; }
  void set_mcause(word_t v) override { state_.mcause = v; }
  [[nodiscard]] word_t mtval() const override { return state_.mtval; }
  void set_mtval(word_t v) override { state_.mtval = v; }
  [[nodiscard]] word_t mvendorid() const override { return state_.mvendorid; }
  [[nodiscard]] word_t marchid() const override { return state_.marchid; }

  void memcpy(paddr_t, void *, size_t, bool) override {
    panic("VerilatorModel::memcpy not supported");
  }

  void display() const override {
    for (int i = 0; i < num_gprs; i++) {
      std::printf("%-4s = " FMT_WORD "  ", gpr_names[i].data(), state_.gpr[i]);
      if ((i + 1) % 4 == 0) std::printf("\n");
    }
    std::printf("pc   = " FMT_WORD "\n", state_.pc);
  }
};

} // namespace npc

static npc::VerilatorModel *g_verilator = nullptr;

bool npc_core_init(int argc, char *argv[]) {
  g_verilator = new npc::VerilatorModel(argc, argv);
  npc::set_dut(g_verilator);
  return g_verilator->init();
}

void npc_core_fini() {
  if (g_verilator) {
    g_verilator->fini();
    delete g_verilator;
    g_verilator = nullptr;
    npc::set_dut(nullptr);
  }
}
