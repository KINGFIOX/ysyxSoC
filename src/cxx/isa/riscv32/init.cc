#include <npc/isa.hh>

namespace npc {

void Cpu::reset() {
  set_pc(RESET_VECTOR);
  set_gpr(0, 0);
}

void Cpu::init() {
  reset();
}

} // namespace npc

void init_isa() {
  npc::cpu().init();
}
