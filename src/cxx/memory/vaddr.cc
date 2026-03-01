#include <npc/trace.hh>
#include <npc/isa.hh>
#include <npc/cpu_model.hh>

word_t vaddr_ifetch(vaddr_t addr, int len) {
  return paddr_read(addr, len);
}

word_t vaddr_read(vaddr_t addr, int len) {
  word_t data = paddr_read(addr, len);
  g_trace.mtrace().on_read(addr, len, data, npc::dut().pc());
  return data;
}

void vaddr_write(vaddr_t addr, int len, word_t data) {
  g_trace.mtrace().on_write(addr, len, data, npc::dut().pc());
  paddr_write(addr, len, data);
}
