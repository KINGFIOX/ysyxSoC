#ifndef NPC_STATE_HH_
#define NPC_STATE_HH_

#include <npc/common.hh>
#include <string_view>

namespace npc {

// C++20 typed wrapper over the legacy C enum
enum class SimState : int {
  Running = NPC_RUNNING,
  Stop    = NPC_STOP,
  End     = NPC_END,
  Abort   = NPC_ABORT,
  Quit    = NPC_QUIT,
};

constexpr std::string_view state_name(SimState s) {
  switch (s) {
  case SimState::Running: return "RUNNING";
  case SimState::Stop:    return "STOP";
  case SimState::End:     return "END";
  case SimState::Abort:   return "ABORT";
  case SimState::Quit:    return "QUIT";
  }
  return "UNKNOWN";
}

// Typed view over the global npc_state (zero-cost, same layout)
struct SimulatorState {
  SimState state() const { return static_cast<SimState>(npc_state.state); }
  void set_state(SimState s) { npc_state.state = static_cast<int>(s); }

  vaddr_t halt_pc() const { return npc_state.halt_pc; }
  uint32_t halt_ret() const { return npc_state.halt_ret; }

  void set_halt(SimState s, vaddr_t pc, uint32_t ret) {
    npc_state.state = static_cast<int>(s);
    npc_state.halt_pc = pc;
    npc_state.halt_ret = ret;
  }

  bool is_good_exit() const {
    return (state() == SimState::End && halt_ret() == 0) ||
           state() == SimState::Quit;
  }

  bool can_continue() const {
    auto s = state();
    return s == SimState::Running || s == SimState::Stop;
  }
};

inline SimulatorState sim_state;

} // namespace npc

#endif // NPC_STATE_HH_
