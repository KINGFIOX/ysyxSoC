#include <npc/cpu.hh>

extern "C" void exception_dpi(int en, int pc, int mcause, int a0, int /* tval */) {
  if (!en) return;
  switch (mcause) {
    case 2: INV((vaddr_t)pc); break;
    default: NPCTRAP((vaddr_t)pc, a0); break;
  }
}
