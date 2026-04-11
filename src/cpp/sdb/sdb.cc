#include "sdb/sdb.h"

#include <readline/history.h>
#include <readline/readline.h>

#include <cstdlib>
#include <string>

#include "absl/log/log.h"
#include "absl/strings/numbers.h"
#include "absl/strings/str_format.h"
#include "absl/strings/str_split.h"
#include "absl/strings/string_view.h"
#include "cpu/abstract_cpu.h"
#include "sdb/command.h"
#include "sdb/expr.h"

namespace npc {

const std::vector<Sdb::CommandDef>& Sdb::GetCommands() {
  static const auto* commands = new std::vector<CommandDef>{
      {{"help", "h"}, "show this help message", &Sdb::cmd_help},
      {{"quit", "q"}, "quit the debugger", &Sdb::cmd_quit},
      {{"continue", "c"}, "continue execution", &Sdb::cmd_continue},
      {{"step", "si", "s"}, "step N instructions (default 1)", &Sdb::cmd_step},
      {{"info"}, "info r(egisters) / info w(atchpoints)", &Sdb::cmd_info},
      {{"examine", "x"}, "x N EXPR - examine N words at EXPR", &Sdb::cmd_examine},
      {{"print", "p", "eval"}, "evaluate expression", &Sdb::cmd_print},
      {{"watch", "w"}, "add watchpoint on expression", &Sdb::cmd_watch},
      {{"delete", "d"}, "delete watchpoint by id", &Sdb::cmd_delete},
      {{"break", "b"}, "set breakpoint at address", &Sdb::cmd_break},
  };
  return *commands;
}

Sdb::Sdb(ScoreBoard& scoreboard, bool enable_fork) : scoreboard_(scoreboard) {
  if (enable_fork) {
    LOG(INFO) << "[lightsss] enabled";
    lightsss_.emplace();
  }
}

absl::Status Sdb::mainloop(VerilatorCpu& dut, bool batch) {
  if (lightsss_.has_value()) {
    auto result = lightsss_->do_fork();
    if (result.is_child) {
      dut.enable_wave();
      LOG(INFO) << absl::StreamFormat(
          "[lightsss] child dumping wave from %lu to %lu...", dut.sim_time(),
          result.end_cycles);
      auto s = dut.run_until(result.end_cycles);
      if (!s.ok()) {
        LOG(INFO) << "[lightsss] child replay stopped early: " << s;
      }
      dut.flush_wave();
      LOG(INFO) << "[lightsss] child wave dump finished, exiting";
      std::exit(0);
    }
  }

  if (batch) {
    auto r = execute_steps(SIZE_MAX, dut);
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

    auto r = execute_line(input, dut);
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

Sdb::CmdResult Sdb::execute_line(const std::string& input, VerilatorCpu& dut) {
  auto cmd = ParseCommand(input);
  if (!cmd.has_value()) return CmdResult::Continue();

  for (const auto& def : GetCommands()) {
    for (const auto& name : def.names) {
      if (cmd->name == name) {
        return (this->*def.handler)(cmd->args, dut);
      }
    }
  }

  return CmdResult::InputError(
      absl::StrFormat("unknown command: %s", cmd->name));
}

Sdb::CmdResult Sdb::execute_steps(size_t n, VerilatorCpu& dut) {
  for (size_t i = 0; i < n; ++i) {
    if (lightsss_.has_value() && lightsss_->should_fork()) {
      auto result = lightsss_->do_fork();
      if (result.is_child) {
        dut.enable_wave();
        LOG(INFO) << absl::StreamFormat(
            "[lightsss] child dumping wave from %lu to %lu...", dut.sim_time(),
            result.end_cycles);
        auto s = dut.run_until(result.end_cycles);
        if (!s.ok()) {
          LOG(INFO) << "[lightsss] child replay stopped early: " << s;
        }
        dut.flush_wave();
        LOG(INFO) << "[lightsss] child wave dump finished, exiting";
        std::exit(0);
      }
    }

    auto s = dut.step();
    if (!s.ok()) {
      return CmdResult::Fatal(std::string(s.message()));
    }

    uint64_t ebreak_a0 = 0;
    auto step_result = scoreboard_.scoreboard(dut, &ebreak_a0);
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

    if (check_breakpoints(dut)) {
      return CmdResult::Continue();
    }
    std::string wp_buf;
    if (watchpoints_.check(dut, wp_buf)) {
      LOG(INFO) << wp_buf;
      return CmdResult::Continue();
    }
  }
  return CmdResult::Continue();
}

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

void Sdb::lightsss_on_error(const VerilatorCpu& dut) {
  if (lightsss_.has_value() && !lightsss_->is_child()) {
    lightsss_->wakeup_child(dut.sim_time());
  }
}

// ========================== Command Handlers ==========================

Sdb::CmdResult Sdb::cmd_help(const std::string& /*args*/,
                             VerilatorCpu& /*dut*/) {
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
                             VerilatorCpu& /*dut*/) {
  return CmdResult::Quit();
}

Sdb::CmdResult Sdb::cmd_continue(const std::string& /*args*/,
                                 VerilatorCpu& dut) {
  return execute_steps(SIZE_MAX, dut);
}

Sdb::CmdResult Sdb::cmd_step(const std::string& args, VerilatorCpu& dut) {
  size_t n = 1;
  if (!args.empty()) {
    if (!absl::SimpleAtoi(args, &n)) {
      return CmdResult::InputError("usage: step [N]");
    }
  }
  return execute_steps(n, dut);
}

Sdb::CmdResult Sdb::cmd_info(const std::string& args, VerilatorCpu& dut) {
  absl::string_view sub = absl::StripAsciiWhitespace(args);
  if (sub == "r" || sub == "registers" || sub == "reg") {
    std::string buf = absl::StrFormat("pc  = 0x%016x\n", dut.pc());
    for (int i = 0; i < 32; ++i) {
      absl::StrAppendFormat(&buf, "%-4s = 0x%016x  ", kGprNames[i],
                            dut.gpr(i).value_or(0));
      if ((i + 1) % 4 == 0) buf += "\n";
    }
    LOG(INFO) << buf;
  } else if (sub == "w" || sub == "watchpoints" || sub == "wp") {
    std::string buf;
    watchpoints_.list(buf);
    LOG(INFO) << buf;
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
  } else {
    return CmdResult::InputError("usage: info r|w|b");
  }
  return CmdResult::Continue();
}

Sdb::CmdResult Sdb::cmd_examine(const std::string& args, VerilatorCpu& dut) {
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

Sdb::CmdResult Sdb::cmd_print(const std::string& args, VerilatorCpu& dut) {
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

Sdb::CmdResult Sdb::cmd_watch(const std::string& args, VerilatorCpu& dut) {
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

Sdb::CmdResult Sdb::cmd_delete(const std::string& args, VerilatorCpu& /*dut*/) {
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

Sdb::CmdResult Sdb::cmd_break(const std::string& args, VerilatorCpu& dut) {
  absl::string_view expr = absl::StripAsciiWhitespace(args);
  if (expr.empty()) {
    return CmdResult::InputError("usage: b ADDR");
  }

  // Sub-commands: b ls, b rm N
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

}  // namespace npc
