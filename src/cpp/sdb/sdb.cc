#include "sdb/sdb.h"

#include <readline/history.h>
#include <readline/readline.h>

#include <string>

#include "absl/log/log.h"
#include "absl/strings/numbers.h"
#include "absl/strings/str_format.h"
#include "absl/strings/str_split.h"
#include "absl/strings/string_view.h"
#include "cpu/cpu_reg_view.h"
#include "sdb/command.h"
#include "sdb/expr.h"
#include "sdb/scoreboard.h"

namespace npc {

auto Sdb::GetCommands() -> const std::vector<Sdb::CommandDef>& {
  static const auto* commands = new std::vector<CommandDef> {
      {{"help", "h"}, "show this help message", &Sdb::cmd_help},
      {{"quit", "q"}, "quit the debugger", &Sdb::cmd_quit},
      {{"continue", "c"}, "continue execution", &Sdb::cmd_continue},
      {{"step", "si", "s"}, "step N instructions (default 1)", &Sdb::cmd_step},
      {{"info"}, "info r(egisters) / info w(atchpoints)", &Sdb::cmd_info},
      {{"examine", "x"}, "x N EXPR - examine N words at EXPR", &Sdb::cmd_examine},
      {{"print", "p", "eval"}, "evaluate expression", &Sdb::cmd_print},
#ifdef NPC_WATCHPOINT
      {{"watch", "w"}, "add watchpoint on expression", &Sdb::cmd_watch},
      {{"delete", "d"}, "delete watchpoint by id", &Sdb::cmd_delete},
#endif
#ifdef NPC_BREAKPOINT
      {{"break", "b"}, "set breakpoint at address", &Sdb::cmd_break},
#endif
  };
  return *commands;
}

auto Sdb::mainloop(ScoreBoard & scrbrd , VerilatorCpu& dut, bool batch) -> absl::Status {
  if (batch) {
    auto r = execute_steps(SIZE_MAX, dut, scrbrd);
    if (r.is_fatal()) {
      return absl::InternalError(r.error_msg);
    }
    if (r.action == Action::kQuit && r.ok) {
      return absl::OkStatus();
    }
  }

  while (true) {
    char* line_raw = readline("(sdb) ");
    if (line_raw == nullptr) {
      return absl::OkStatus();  // EOF
    }
    std::string line(line_raw);
    free(line_raw);

    // Trim
    while (!line.empty() &&
           (line.back() == ' ' || line.back() == '\t' || line.back() == '\n')) {
      line.pop_back();
    }

    std::string input;
    if (line.empty()) {
      if (last_cmd_.has_value()) {
        input = *last_cmd_;
      } else {
        continue;
      }
    } else {
      add_history(line.c_str());
      last_cmd_ = line;
      input = line;
    }

    auto r = execute_line(input, dut, scrbrd);
    if (r.is_fatal()) {
      return absl::InternalError(r.error_msg);
    }
    if (!r.ok && !r.is_fatal()) {
      LOG(WARNING) << r.error_msg;
    }
    if (r.action == Action::kQuit) {
      return absl::OkStatus();
    }
  }
}

Sdb::CmdResult Sdb::execute_line(const std::string& input, VerilatorCpu& dut, ScoreBoard& scrbrd) {
  auto cmd = ParseCommand(input);
  if (!cmd.has_value()) return CmdResult::Continue();

  for (const auto& def : GetCommands()) {
    for (const auto& name : def.names) {
      if (cmd->name == name) {
        return (this->*def.handler)(cmd->args, dut, scrbrd);
      }
    }
  }

  return CmdResult::InputError(
      absl::StrFormat("unknown command: %s", cmd->name));
}

Sdb::CmdResult Sdb::execute_steps(size_t n, VerilatorCpu& dut, ScoreBoard & scrbrd) {
  for (size_t i = 0; i < n; ++i) {
    auto s = dut.step();
    if (!s.ok()) {
      return CmdResult::Fatal(std::string(s.message()));
    }

    uint64_t ebreak_a0 = 0;
    auto step_result = scrbrd.scoreboard(dut, &ebreak_a0);
    switch (step_result) {
      case StepResult::kContinue:
        break;
      case StepResult::kEBreak:
        if (ebreak_a0 == 0) {
          LOG(INFO) << "program exited successfully";
          return CmdResult::Quit();
        }
        return CmdResult::Fatal(absl::StrFormat(
            "program exited with failure (a0 = 0x%x)", ebreak_a0));
      case StepResult::kDifftestFail:
        return CmdResult::Fatal("difftest failed");
    }

#ifdef NPC_BREAKPOINT
    if (check_breakpoints(dut)) {
      return CmdResult::Continue();
    }
#endif

#ifdef NPC_WATCHPOINT
    std::string wp_buf;
    if (watchpoints_.check(dut, wp_buf)) {
      LOG(INFO) << wp_buf;
      return CmdResult::Continue();
    }
#endif

  }
  return CmdResult::Continue();
}

#ifdef NPC_BREAKPOINT
bool Sdb::check_breakpoints(const VerilatorCpu& dut) const {
  uint64_t pc = dut.pc();
  for (uint64_t bp : breakpoints_) {
    if (pc == bp) {
      LOG(INFO) << absl::StreamFormat("breakpoint hit at 0x%016x", pc);
      return true;
    }
  }
  return false;
}
#endif

// ========================== Command Handlers ==========================

auto Sdb::cmd_help(const std::string& /*args*/, VerilatorCpu& /*dut*/, ScoreBoard&)
    -> Sdb::CmdResult {
  std::string buf = "Commands:\n";
  for (const auto& def : GetCommands()) {
    std::string names;
    for (size_t i = 0; i < def.names.size(); ++i) {
      if (i > 0) names += ", ";
      names += def.names[i];
    }
    absl::StrAppendFormat(&buf, "  %-20s %s\n", names, def.help);
  }
  LOG(INFO) << buf;
  return CmdResult::Continue();
}

Sdb::CmdResult Sdb::cmd_quit(const std::string& /*args*/,
                             VerilatorCpu& /*dut*/, ScoreBoard&) {
  return CmdResult::Quit();
}

Sdb::CmdResult Sdb::cmd_continue(const std::string& /*args*/,
                                 VerilatorCpu& dut, ScoreBoard& scrbrd) {
  return execute_steps(SIZE_MAX, dut, scrbrd);
}

Sdb::CmdResult Sdb::cmd_step(const std::string& args, VerilatorCpu& dut, ScoreBoard& scrbrd) {
  size_t n = 1;
  if (!args.empty()) {
    if (!absl::SimpleAtoi(args, &n)) {
      return CmdResult::InputError("usage: step [N]");
    }
  }
  return execute_steps(n, dut, scrbrd);
}

Sdb::CmdResult Sdb::cmd_info(const std::string& args, VerilatorCpu& dut, ScoreBoard&) {
  absl::string_view sub = absl::StripAsciiWhitespace(args);
  if (sub == "r" || sub == "registers" || sub == "reg") {
    std::string buf = absl::StrFormat("pc  = 0x%016x\n", dut.pc());
    for (int i = 0; i < 32; ++i) {
      absl::StrAppendFormat(&buf, "%-4s = 0x%016x  ", kGprNames[i],
                            dut.gpr(i).value_or(0));
      if ((i + 1) % 4 == 0) buf += "\n";
    }
    LOG(INFO) << buf;
#ifdef NPC_WATCHPOINT
  } else if (sub == "w" || sub == "watchpoints" || sub == "wp") {
    std::string buf;
    watchpoints_.list(buf);
    LOG(INFO) << buf;
#endif
#ifdef NPC_BREAKPOINT
  } else if (sub == "b" || sub == "breakpoints" || sub == "bp") {
    if (breakpoints_.empty()) {
      LOG(INFO) << "no breakpoints";
    } else {
      std::string buf;
      for (size_t i = 0; i < breakpoints_.size(); ++i) {
        absl::StrAppendFormat(&buf, "  #%d: 0x%016x\n", i + 1, breakpoints_[i]);
      }
      LOG(INFO) << buf;
    }
#endif
  } else {
    return CmdResult::InputError("usage: info r"
#ifdef NPC_WATCHPOINT
        "|w"
#endif
#ifdef NPC_BREAKPOINT
        "|b"
#endif
    );
  }
  return CmdResult::Continue();
}

Sdb::CmdResult Sdb::cmd_examine(const std::string& args, VerilatorCpu& dut, ScoreBoard&) {
  std::vector<absl::string_view> parts =
      absl::StrSplit(args, absl::MaxSplits(' ', 1), absl::SkipEmpty());
  if (parts.size() < 2) {
    return CmdResult::InputError("usage: x N EXPR");
  }
  size_t n = 0;
  if (!absl::SimpleAtoi(parts[0], &n)) {
    return CmdResult::InputError(absl::StrFormat("bad count: %s", parts[0]));
  }
  auto addr = ExprEval(parts[1], dut);
  if (!addr.ok()) {
    return CmdResult::InputError(
        absl::StrFormat("expression error: %s", addr.status().message()));
  }

  std::string buf;
  for (size_t i = 0; i < n; ++i) {
    uint64_t a = *addr + i * 4;
    if (i % 4 == 0) {
      absl::StrAppendFormat(&buf, "0x%016x:", a);
    }
    auto val = dut.mem_load(a, 4);
    if (val.ok()) {
      absl::StrAppendFormat(&buf, "  0x%016x", *val);
    } else {
      buf += "  ??????????????????";
    }
    if ((i + 1) % 4 == 0 || i + 1 == n) buf += "\n";
  }
  LOG(INFO) << buf;
  return CmdResult::Continue();
}

Sdb::CmdResult Sdb::cmd_print(const std::string& args, VerilatorCpu& dut, ScoreBoard&) {
  absl::string_view expr = absl::StripAsciiWhitespace(args);
  if (expr.empty()) {
    return CmdResult::InputError("usage: p EXPR");
  }
  auto val = ExprEval(expr, dut);
  if (!val.ok()) {
    return CmdResult::InputError(
        absl::StrFormat("expression error: %s", val.status().message()));
  }
  LOG(INFO) << absl::StreamFormat("0x%016x (%lu)", *val, *val);
  return CmdResult::Continue();
}

#ifdef NPC_WATCHPOINT
Sdb::CmdResult Sdb::cmd_watch(const std::string& args, VerilatorCpu& dut, ScoreBoard&) {
  absl::string_view expr = absl::StripAsciiWhitespace(args);
  if (expr.empty()) {
    return CmdResult::InputError("usage: w EXPR");
  }
  auto id = watchpoints_.add(std::string(expr), dut);
  if (!id.ok()) {
    return CmdResult::InputError(
        absl::StrFormat("expression error: %s", id.status().message()));
  }
  LOG(INFO) << absl::StreamFormat("watchpoint #%d: %s", *id, expr);
  return CmdResult::Continue();
}

Sdb::CmdResult Sdb::cmd_delete(const std::string& args, VerilatorCpu& /*dut*/, ScoreBoard&) {
  int id = 0;
  if (!absl::SimpleAtoi(absl::StripAsciiWhitespace(args), &id)) {
    return CmdResult::InputError("usage: d N");
  }
  if (watchpoints_.remove(id)) {
    LOG(INFO) << absl::StreamFormat("deleted watchpoint #%d", id);
  } else {
    return CmdResult::InputError(
        absl::StrFormat("watchpoint #%d not found", id));
  }
  return CmdResult::Continue();
}
#endif

#ifdef NPC_BREAKPOINT
Sdb::CmdResult Sdb::cmd_break(const std::string& args, VerilatorCpu& dut, ScoreBoard&) {
  absl::string_view expr = absl::StripAsciiWhitespace(args);
  if (expr.empty()) {
    return CmdResult::InputError("usage: b ADDR");
  }

  absl::string_view first_word = expr.substr(0, expr.find(' '));
  if (first_word == "ls" || first_word == "list") {
    if (breakpoints_.empty()) {
      LOG(INFO) << "no breakpoints";
    } else {
      std::string buf;
      for (size_t i = 0; i < breakpoints_.size(); ++i) {
        absl::StrAppendFormat(&buf, "  #%d: 0x%016x\n", i + 1, breakpoints_[i]);
      }
      LOG(INFO) << buf;
    }
    return CmdResult::Continue();
  }

  if (first_word == "rm" || first_word == "remove") {
    absl::string_view rest =
        absl::StripLeadingAsciiWhitespace(expr.substr(first_word.size()));
    size_t idx = 0;
    if (!absl::SimpleAtoi(rest, &idx) || idx < 1 || idx > breakpoints_.size()) {
      return CmdResult::InputError("usage: b rm N");
    }
    uint64_t addr = breakpoints_[idx - 1];
    breakpoints_.erase(breakpoints_.begin() + static_cast<ptrdiff_t>(idx - 1));
    LOG(INFO) << absl::StreamFormat("deleted breakpoint #%d at 0x%016x", idx,
                                    addr);
    return CmdResult::Continue();
  }

  auto addr = ExprEval(expr, dut);
  if (!addr.ok()) {
    return CmdResult::InputError(
        absl::StrFormat("expression error: %s", addr.status().message()));
  }
  breakpoints_.push_back(*addr);
  LOG(INFO) << absl::StreamFormat("breakpoint #%d at 0x%016x",
                                  breakpoints_.size(), *addr);
  return CmdResult::Continue();
}
#endif

}  // namespace npc
