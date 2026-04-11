#ifndef NPC_SDB_SDB_H_
#define NPC_SDB_SDB_H_

#include <cstdint>
#include <optional>
#include <string>
#include <vector>

#include "absl/status/status.h"
#include "common/lightsss.h"
#include "cpu/verilator_cpu.h"
#include "sdb/scoreboard.h"
#include "sdb/watchpoint.h"

namespace npc {

class Sdb {
 public:
  Sdb(ScoreBoard& scoreboard, bool enable_fork);
  ~Sdb() = default;

  Sdb(const Sdb&) = delete;
  Sdb& operator=(const Sdb&) = delete;

  absl::Status mainloop(VerilatorCpu& dut, bool batch);
  void lightsss_on_error(const VerilatorCpu& dut);

 private:
  enum class Action { kContinue, kQuit };

  struct CmdResult {
    bool ok;
    Action action;
    std::string error_msg;

    static CmdResult Continue() { return {.ok=true, .action=Action::kContinue, .error_msg=""}; }
    static CmdResult Quit() { return {true, Action::kQuit, ""}; }
    static CmdResult InputError(std::string msg) {
      return {false, Action::kContinue, std::move(msg)};
    }
    static CmdResult Fatal(std::string msg) {
      return {false, Action::kQuit, std::move(msg)};
    }
    bool is_fatal() const { return !ok && action == Action::kQuit; }
  };

  using CmdHandler = CmdResult (Sdb::*)(const std::string&, VerilatorCpu&);

  struct CommandDef {
    std::vector<std::string> names;
    std::string help;
    CmdHandler handler;
  };

  static const std::vector<CommandDef>& GetCommands();

  CmdResult execute_line(const std::string& input, VerilatorCpu& dut);
  CmdResult execute_steps(size_t n, VerilatorCpu& dut);
  bool check_breakpoints(const VerilatorCpu& dut) const;

  CmdResult cmd_help(const std::string& args, VerilatorCpu& dut);
  CmdResult cmd_quit(const std::string& args, VerilatorCpu& dut);
  CmdResult cmd_continue(const std::string& args, VerilatorCpu& dut);
  CmdResult cmd_step(const std::string& args, VerilatorCpu& dut);
  CmdResult cmd_info(const std::string& args, VerilatorCpu& dut);
  CmdResult cmd_examine(const std::string& args, VerilatorCpu& dut);
  CmdResult cmd_print(const std::string& args, VerilatorCpu& dut);
  CmdResult cmd_watch(const std::string& args, VerilatorCpu& dut);
  CmdResult cmd_delete(const std::string& args, VerilatorCpu& dut);
  CmdResult cmd_break(const std::string& args, VerilatorCpu& dut);

  ScoreBoard& scoreboard_;
  std::vector<uint64_t> breakpoints_;
  WatchpointPool watchpoints_;
  std::optional<std::string> last_cmd_;
  std::optional<LightSSS> lightsss_;
};

}  // namespace npc

#endif  // NPC_SDB_SDB_H_
