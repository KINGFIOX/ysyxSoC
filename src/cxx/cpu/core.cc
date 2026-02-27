#include <npc/cpu.hh>
#include <npc/isa.hh>

void init_sram(uint8_t *verilator_sram_ptr);

#include "VNPCSoC.h"
#include "VNPCSoC___024root.h"
#include <verilated.h>

#ifdef CONFIG_VERILATOR_TRACE
#include <verilated_vcd_c.h>
#endif

static VNPCSoC *top = nullptr;
static VerilatedContext *ctx = nullptr;
#ifdef CONFIG_VERILATOR_TRACE
static VerilatedVcdC *tfp = nullptr;
static uint64_t sim_time = 0;
#endif

static uint64_t ncycles = 0;

#define DEBUG_GPR(n) top->debug_gpr_##n

static void tick() {
  top->clock = 0;
  top->eval();
#ifdef CONFIG_VERILATOR_TRACE
  tfp->dump(sim_time++);
#endif

  top->clock = 1;
  top->eval();
  ncycles++;
#ifdef CONFIG_VERILATOR_TRACE
  tfp->dump(sim_time++);
#endif
}

static void reset(int cycles = 15) {
  top->reset = 1;
  for (int i = 0; i < cycles; i++) {
    tick();
  }
  top->reset = 0;
}

bool npc_core_init(int argc, char *argv[]) {
  ctx = new VerilatedContext;
  ctx->commandArgs(argc, argv);

  top = new VNPCSoC(ctx);

#ifdef CONFIG_VERILATOR_TRACE
  Verilated::traceEverOn(true);
  tfp = new VerilatedVcdC;
  top->trace(tfp, 99);
  tfp->open("build/npc_core.vcd");
  Log("VCD trace enabled: build/npc_core.vcd");
#endif

  reset();

#define VERILATOR_SRAM_MEMORY                                                  \
  top->rootp                                                                   \
      ->NPCSoC__DOT__dut__DOT__asic__DOT__axi4ram__DOT__mem_ext__DOT__Memory

  init_sram(reinterpret_cast<uint8_t *>(VERILATOR_SRAM_MEMORY.data()));

  Log("Verilator core initialized, reset complete");
  return true;
}

void npc_core_flush_trace() {
#ifdef CONFIG_VERILATOR_TRACE
  if (tfp) {
    tfp->flush();
  }
#endif
}

void npc_core_fini() {
#ifdef CONFIG_VERILATOR_TRACE
  if (tfp) {
    tfp->close();
    delete tfp;
    tfp = nullptr;
  }
#endif

  if (top) {
    top->final();
    delete top;
    top = nullptr;
  }

  if (ctx) {
    delete ctx;
    ctx = nullptr;
  }
  Log("Verilator core finalized");
  Log("total cycles: {}", ncycles);
}

static void read_debug_to_decode(Decode *s) {
  s->pc = top->debug_pc;
  s->dnpc = top->debug_dnpc;
  s->snpc = s->pc + 4;
  s->isa.inst = top->debug_inst;
}

static void sync_gpr_to_cpu() {
  auto &c = npc::cpu();
  c.set_gpr(0, DEBUG_GPR(0));
  c.set_gpr(1, DEBUG_GPR(1));
  c.set_gpr(2, DEBUG_GPR(2));
  c.set_gpr(3, DEBUG_GPR(3));
  c.set_gpr(4, DEBUG_GPR(4));
  c.set_gpr(5, DEBUG_GPR(5));   // NOLINT
  c.set_gpr(6, DEBUG_GPR(6));   // NOLINT
  c.set_gpr(7, DEBUG_GPR(7));   // NOLINT
  c.set_gpr(8, DEBUG_GPR(8));   // NOLINT
  c.set_gpr(9, DEBUG_GPR(9));   // NOLINT
  c.set_gpr(10, DEBUG_GPR(10)); // NOLINT
  c.set_gpr(11, DEBUG_GPR(11)); // NOLINT
  c.set_gpr(12, DEBUG_GPR(12)); // NOLINT
  c.set_gpr(13, DEBUG_GPR(13)); // NOLINT
  c.set_gpr(14, DEBUG_GPR(14)); // NOLINT
  c.set_gpr(15, DEBUG_GPR(15)); // NOLINT
  c.set_gpr(16, DEBUG_GPR(16)); // NOLINT
  c.set_gpr(17, DEBUG_GPR(17)); // NOLINT
  c.set_gpr(18, DEBUG_GPR(18)); // NOLINT
  c.set_gpr(19, DEBUG_GPR(19)); // NOLINT
  c.set_gpr(20, DEBUG_GPR(20)); // NOLINT
  c.set_gpr(21, DEBUG_GPR(21)); // NOLINT
  c.set_gpr(22, DEBUG_GPR(22)); // NOLINT
  c.set_gpr(23, DEBUG_GPR(23)); // NOLINT
  c.set_gpr(24, DEBUG_GPR(24)); // NOLINT
  c.set_gpr(25, DEBUG_GPR(25)); // NOLINT
  c.set_gpr(26, DEBUG_GPR(26)); // NOLINT
  c.set_gpr(27, DEBUG_GPR(27)); // NOLINT
  c.set_gpr(28, DEBUG_GPR(28)); // NOLINT
  c.set_gpr(29, DEBUG_GPR(29)); // NOLINT
  c.set_gpr(30, DEBUG_GPR(30)); // NOLINT
  c.set_gpr(31, DEBUG_GPR(31)); // NOLINT
}

static void sync_csr_to_cpu() {
  auto &c = npc::cpu();
  c.write_csr(MSTATUS, top->debug_csr_mstatus);
  c.write_csr(MTVEC, top->debug_csr_mtvec);
  c.write_csr(MEPC, top->debug_csr_mepc);
  c.write_csr(MCAUSE, top->debug_csr_mcause);
  c.write_csr(MTVAL, top->debug_csr_mtval);
  c.write_csr(MVENDORID, top->debug_csr_mvendorid);
  c.write_csr(MARCHID, top->debug_csr_marchid);
}

bool npc_core_step(Decode *s) {
  top->step = 1;

  const int MAX_CYCLES = 1000000;
  int cycles = 0;
  do {
    tick();
    cycles++;
    if (cycles >= MAX_CYCLES) {
      Log("Warning: npc_core_step exceeded {} cycles without debug_commit",
          MAX_CYCLES);
      return false;
    }
  } while (!top->debug_valid);

  read_debug_to_decode(s);
  npc::cpu().set_pc(s->dnpc);
  sync_gpr_to_cpu();
  sync_csr_to_cpu();

  top->step = 0;
  top->eval();
  return true;
}
