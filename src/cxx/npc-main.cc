#include <npc/state.hh>
#include <npc/cpu.hh>

void init_monitor(int, char *[]);
void engine_start();

int main(int argc, char *argv[]) {
  init_monitor(argc, argv);
  engine_start();
  npc_core_fini();
  return npc::sim_state.is_good_exit() ? 0 : 1;
}
