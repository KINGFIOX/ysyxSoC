#include <npc/trace.hh>
#include <npc/isa.hh>

word_t vaddr_ifetch(vaddr_t addr, int len) {
  return paddr_read(addr, len);
}

word_t vaddr_read(vaddr_t addr, int len) {
  word_t data = paddr_read(addr, len);
  g_trace.mtrace().on_read(addr, len, data, cpu.pc);
  return data;
}

void vaddr_write(vaddr_t addr, int len, word_t data) {
  g_trace.mtrace().on_write(addr, len, data, cpu.pc);
  paddr_write(addr, len, data);
}
