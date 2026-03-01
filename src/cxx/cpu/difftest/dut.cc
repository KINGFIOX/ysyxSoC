#include <npc/difftest.hh>
#include <npc/isa.hh>

#ifdef CONFIG_DIFFTEST

#include <npc/cpu_model.hh>

namespace npc {

static void print_reg_row(const char *name, word_t ref_val, word_t dut_val) {
  bool is_diff = (ref_val != dut_val);
  if (is_diff) {
    printf("| %s%-4s%s | %s" FMT_WORD "%s | %s" FMT_WORD "%s | %sMISMATCH%s |\n",
           ANSI_FG_RED, name, ANSI_NONE,
           ANSI_FG_GREEN, ref_val, ANSI_NONE,
           ANSI_FG_RED, dut_val, ANSI_NONE,
           ANSI_FG_RED, ANSI_NONE);
  } else {
    printf("| %-4s | " FMT_WORD " | " FMT_WORD " | OK       |\n",
           name, ref_val, dut_val);
  }
}

static void print_diff_table(CpuModel &dut, CpuModel &ref, vaddr_t pc) {
  printf("\n");
  printf("+------+------------+------------+----------+\n");
  printf("|   %sDifftest FAILED at PC = " FMT_WORD "%s    |\n", ANSI_FG_YELLOW, pc, ANSI_NONE);
  printf("+------+------------+------------+----------+\n");
  printf("| Reg  | REF        | DUT        | Status   |\n");
  printf("+------+------------+------------+----------+\n");

  for (int i = 0; i < num_gprs; i++) {
    print_reg_row(reg_name(i), ref.gpr(i), dut.gpr(i));
  }

  printf("+------+------------+------------+----------+\n");
  print_reg_row("pc", ref.pc(), dut.pc());

  printf("+------+------------+------------+----------+\n");
  printf("\n");
}

static bool compare_states(CpuModel &dut, CpuModel &ref) {
  for (int i = 0; i < num_gprs; i++) {
    if (ref.gpr(i) != dut.gpr(i)) return false;
  }
  if (ref.pc() != dut.pc()) return false;
  return true;
}

static void sync_ref_from_dut(CpuModel &dut, CpuModel &ref) {
  ref.set_pc(dut.pc());
  for (int i = 0; i < num_gprs; i++)
    ref.set_gpr(i, dut.gpr(i));
  ref.set_mstatus(dut.mstatus());
  ref.set_mtvec(dut.mtvec());
  ref.set_mepc(dut.mepc());
  ref.set_mcause(dut.mcause());
  ref.set_mtval(dut.mtval());
}

bool Scoreboard::check(CpuModel &dut_model, CpuModel &ref_model,
                        const StepResult &dut_result) {
  if (dut_result.is_mmio) {
    sync_ref_from_dut(dut_model, ref_model);
    return true;
  }

  ref_model.step();

  if (!compare_states(dut_model, ref_model)) {
    print_diff_table(dut_model, ref_model, dut_result.pc);
    npc_state.state = NPC_ABORT;
    npc_state.halt_pc = dut_result.pc;
    return false;
  }
  return true;
}

} // namespace npc

#endif
