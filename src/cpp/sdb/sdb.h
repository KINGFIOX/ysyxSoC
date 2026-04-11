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
  explicit Sdb(bool enable_fork);
  ~Sdb() = default;

  Sdb(const Sdb&) = delete;
  auto operator=(const Sdb&) -> Sdb& = delete;

  auto mainloop(ScoreBoard& scrbrd, VerilatorCpu& dut, bool batch) -> absl::Status;
  void lightsss_on_error(const VerilatorCpu& dut);

 private:
  enum class Action { kContinue, kQuit };

  struct CmdResult {
    bool ok;
    Action action;
    std::string error_msg;

    static auto Continue() -> CmdResult {
      return {.ok = true, .action = Action::kContinue, .error_msg = ""};
    }
    static auto Quit() -> CmdResult {
      return {.ok = true, .action = Action::kQuit, .error_msg = ""};
    }
    static auto InputError(std::string msg) -> CmdResult {
      return {.ok = false,
              .action = Action::kContinue,
              .error_msg = std::move(msg)};
    }
    static auto Fatal(std::string msg) -> CmdResult {
      return {
          .ok = false, .action = Action::kQuit, .error_msg = std::move(msg)};
    }

    [[nodiscard]] auto is_fatal() const -> bool { return !ok && action == Action::kQuit; }

  };

  using CmdHandler = CmdResult (Sdb::*)(const std::string&, VerilatorCpu&, ScoreBoard&);

  struct CommandDef {
    std::vector<std::string> names;
    std::string help;
    CmdHandler handler;
  };

  static auto GetCommands() -> const std::vector<CommandDef>&;

  auto execute_line(const std::string& input, VerilatorCpu& dut, ScoreBoard & scrbrd) -> CmdResult;
  auto execute_steps(size_t n, VerilatorCpu& dut, ScoreBoard & scrbrd) -> CmdResult;
  [[nodiscard]] auto check_breakpoints(const VerilatorCpu& dut) const -> bool;

  auto cmd_help(const std::string& args, VerilatorCpu& dut    , ScoreBoard& scrbrd) -> CmdResult;
  auto cmd_quit(const std::string& args, VerilatorCpu& dut    , ScoreBoard& scrbrd) -> CmdResult;
  auto cmd_continue(const std::string& args, VerilatorCpu& dut, ScoreBoard& scrbrd) -> CmdResult;
  auto cmd_step(const std::string& args, VerilatorCpu& dut    , ScoreBoard& scrbrd) -> CmdResult;
  auto cmd_info(const std::string& args, VerilatorCpu& dut    , ScoreBoard& scrbrd) -> CmdResult;
  auto cmd_examine(const std::string& args, VerilatorCpu& dut , ScoreBoard& scrbrd) -> CmdResult;
  auto cmd_print(const std::string& args, VerilatorCpu& dut   , ScoreBoard& scrbrd) -> CmdResult;
  auto cmd_watch(const std::string& args, VerilatorCpu& dut   , ScoreBoard& scrbrd) -> CmdResult;
  auto cmd_delete(const std::string& args, VerilatorCpu& dut  , ScoreBoard& scrbrd) -> CmdResult;
  auto cmd_break(const std::string& args, VerilatorCpu& dut   , ScoreBoard& scrbrd) -> CmdResult;

  std::vector<uint64_t> breakpoints_;
  WatchpointPool watchpoints_;
  std::optional<std::string> last_cmd_;
  std::optional<LightSSS> lightsss_;
};

}  // namespace npc

#endif  // NPC_SDB_SDB_H_
