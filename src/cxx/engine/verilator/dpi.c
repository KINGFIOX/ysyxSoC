#include "cpu/difftest.h"
#include <cpu/cpu.h>

void exception_dpi(int en, int pc, int mcause, int a0, int tval) {
  if (!en) return;
  IFDEF(CONFIG_DIFFTEST, ref_difftest_raise_intr(a0, tval));
  IFDEF(CONFIG_DIFFTEST, difftest_skip_ref());
  switch (mcause) {
    case 2: INV((vaddr_t)pc); break;
    default: NPCTRAP((vaddr_t)pc, a0); break;
  }
}

// CIRCT DPI ABI: en 参数用于条件调用
void difftest_skip_ref_dpi(int en) {
  if (!en) return;
  difftest_skip_ref();
}
