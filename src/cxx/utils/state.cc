#include <npc/state.hh>

NPCState npc_state = {NPC_STOP, 0, 0};

int is_exit_status_bad() {
  return !npc::sim_state.is_good_exit();
}
