#include "sdb.hh"
#include <npc/cpu.hh>
#include <npc/isa.hh>
#include <npc/state.hh>

#include <array>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <functional>
#include <readline/history.h>
#include <readline/readline.h>
#include <string_view>

namespace {

bool is_batch_mode = false;

char *rl_gets() {
  static char *line_read = nullptr;
  if (line_read) {
    std::free(line_read);
    line_read = nullptr;
  }
  line_read = readline("(npc) ");
  if (line_read && *line_read)
    add_history(line_read);
  return line_read;
}

int cmd_c(char *) {
  cpu_exec(static_cast<uint64_t>(-1));
  return 0;
}

int cmd_q(char *) {
  npc_state.state = NPC_QUIT;
  return -1;
}

int cmd_si(char *args) {
  int steps = 1;
  if (args) {
    steps = static_cast<int>(std::strtol(args, nullptr, 0));
    if (steps <= 0) {
      std::printf("invalid number of steps: %s\n", args);
      return 0;
    }
  }
  cpu_exec(steps);
  return 0;
}

int cmd_info(char *args);

int cmd_x(char *args) {
  if (!args) { std::printf("usage: x N EXPR\n"); return 0; }
  char *n_str = std::strtok(args, " ");
  char *expr_str = std::strtok(nullptr, "");
  if (!n_str || !expr_str) { std::printf("usage: x N EXPR\n"); return 0; }

  int n = static_cast<int>(std::strtol(n_str, nullptr, 0));
  if (n <= 0) {
    std::printf("invalid number of times: %s\n", n_str);
    return 0;
  }

  auto addr_opt = expr_eval(expr_str);
  if (!addr_opt) {
    std::printf("expression evaluation failed: %s\n", parse_error_msg);
    return 0;
  }
  vaddr_t addr = *addr_opt;

  for (int i = 0; i < n; i++) {
    vaddr_t cur = addr + i * sizeof(word_t);
    word_t val = vaddr_read(cur, sizeof(word_t));
    std::printf(FMT_PADDR ": " FMT_WORD "\n", cur, val);
  }
  return 0;
}

int cmd_p(char *args) {
  if (!args) { std::printf("usage: p EXPR\n"); return 0; }
  auto val_opt = expr_eval(args);
  if (!val_opt) {
    std::printf("expression evaluation failed: %s\n", parse_error_msg);
    return 0;
  }
  std::printf(FMT_WORD "\n", *val_opt);
  return 0;
}

int cmd_w(char *args) {
  if (!args) { std::printf("usage: w EXPR\n"); return 0; }
  add_watchpoint(args);
  return 0;
}

int cmd_b(char *args) {
  if (!args) { std::printf("usage: b ADDR\n"); return 0; }
  int n = static_cast<int>(std::strlen(args));
  args[n]   = '=';
  args[n+1] = '=';
  args[n+2] = '$';
  args[n+3] = 'p';
  args[n+4] = 'c';
  args[n+5] = '\0';
  add_watchpoint(args);
  return 0;
}

int cmd_d(char *args) {
  if (!args) { std::printf("usage: d N\n"); return 0; }
  int no = static_cast<int>(std::strtol(args, nullptr, 0));
  delete_watchpoint(no);
  return 0;
}

int cmd_help(char *args);

struct CmdEntry {
  std::string_view name;
  std::string_view description;
  int (*handler)(char *);
};

constexpr std::array cmd_table = {
    CmdEntry{"help", "Display information about all supported commands", cmd_help},
    CmdEntry{"c",    "Continue the execution of the program",           cmd_c},
    CmdEntry{"q",    "Exit NPC",                                        cmd_q},
    CmdEntry{"si",   "Step one instruction (si [N])",                   cmd_si},
    CmdEntry{"info", "Display registers (info r) or watchpoints (info w)", cmd_info},
    CmdEntry{"x",    "View memory (x N EXPR)",                         cmd_x},
    CmdEntry{"p",    "Print expression (p EXPR)",                      cmd_p},
    CmdEntry{"w",    "Set watchpoint (w EXPR)",                        cmd_w},
    CmdEntry{"d",    "Delete watchpoint (d N)",                        cmd_d},
    CmdEntry{"b",    "Set breakpoint (b ADDR)",                        cmd_b},
};

int cmd_help(char *args) {
  if (!args) {
    for (const auto &cmd : cmd_table)
      std::printf("%-6.*s - %.*s\n",
                  static_cast<int>(cmd.name.size()), cmd.name.data(),
                  static_cast<int>(cmd.description.size()), cmd.description.data());
  } else {
    for (const auto &cmd : cmd_table) {
      if (cmd.name == args) {
        std::printf("%-6.*s - %.*s\n",
                    static_cast<int>(cmd.name.size()), cmd.name.data(),
                    static_cast<int>(cmd.description.size()), cmd.description.data());
        return 0;
      }
    }
    std::printf("Unknown command '%s'\n", args);
  }
  return 0;
}

int cmd_info(char *args) {
  if (!args) {
    std::printf("info r  - display registers\n");
    std::printf("info w  - display watchpoints\n");
  } else if (std::string_view{args} == "r") {
    isa_reg_display();
  } else if (std::string_view{args} == "w") {
    list_watchpoints();
  } else {
    std::printf("Unknown subcommand '%s'\n", args);
  }
  return 0;
}

} // anonymous namespace

void sdb_set_batch_mode() { is_batch_mode = true; }

void sdb_mainloop() {
  if (is_batch_mode) {
    cmd_c(nullptr);
    return;
  }

  char last_cmd[256] = "";
  char cmd_buf[256];

  for (char *str; (str = rl_gets()) != nullptr;) {
    if (str[0] == '\0') {
      if (last_cmd[0] == '\0') continue;
      std::strncpy(cmd_buf, last_cmd, sizeof(cmd_buf) - 1);
      cmd_buf[sizeof(cmd_buf) - 1] = '\0';
    } else {
      std::strncpy(cmd_buf, str, sizeof(cmd_buf) - 1);
      cmd_buf[sizeof(cmd_buf) - 1] = '\0';
    }

    char *buf_end = cmd_buf + std::strlen(cmd_buf);
    char *cmd = std::strtok(cmd_buf, " ");
    if (!cmd) continue;

    char *args = cmd + std::strlen(cmd) + 1;
    if (args >= buf_end) args = nullptr;

    bool found = false;
    for (const auto &entry : cmd_table) {
      if (entry.name == cmd) {
        if (args)
          std::snprintf(last_cmd, sizeof(last_cmd), "%s %s", cmd, args);
        else {
          std::strncpy(last_cmd, cmd, sizeof(last_cmd) - 1);
          last_cmd[sizeof(last_cmd) - 1] = '\0';
        }
        if (entry.handler(args) < 0) return;
        found = true;
        break;
      }
    }

    if (!found) std::printf("Unknown command '%s'\n", cmd);
  }
}

void init_sdb() {
#ifdef CONFIG_WATCHPOINT
  init_wp_pool();
#endif
}
