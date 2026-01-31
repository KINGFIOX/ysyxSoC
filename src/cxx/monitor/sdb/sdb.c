/***************************************************************************************
 * Copyright (c) 2014-2024 Zihao Yu, Nanjing University
 *
 * NPC is licensed under Mulan PSL v2.
 * You can use this software according to the terms and conditions of the Mulan
 *PSL v2. You may obtain a copy of Mulan PSL v2 at:
 *          http://license.coscl.org.cn/MulanPSL2
 *
 * THIS SOFTWARE IS PROVIDED ON AN "AS IS" BASIS, WITHOUT WARRANTIES OF ANY
 *KIND, EITHER EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO
 *NON-INFRINGEMENT, MERCHANTABILITY OR FIT FOR A PARTICULAR PURPOSE.
 *
 * See the Mulan PSL v2 for more details.
 ***************************************************************************************/

#include "sdb.h"
#include "utils.h"
#include <cpu/cpu.h>
#include <isa.h>
#include <memory/vaddr.h>
#include <readline/history.h>
#include <readline/readline.h>

static int is_batch_mode = false;

/* We use the `readline' library to provide more flexibility to read from stdin.
 */
static char *rl_gets() {
  static char *line_read = NULL;

  if (line_read) {
    free(line_read);
    line_read = NULL;
  }

  line_read = readline("(npc) ");

  if (line_read && *line_read) {
    add_history(line_read);
  }

  return line_read;
}

static int cmd_c(char *args) {
  cpu_exec(-1);
  return 0;
}

static int cmd_q(char *args) {
  npc_state.state = NPC_QUIT;
  return -1;
}

static int cmd_si(char *args) {
  int steps = 1;      // 缺省为1
  if (args != NULL) { // 不该有参数
    steps = strtol(args, NULL, 0);
    if (steps <= 0) {
      printf("invalid number of steps: %s\n", args);
      return 0;
    }
  }
  cpu_exec(steps);
  return 0;
}

static int cmd_info(char *args);

static int cmd_x(char *args) {
  if (args == NULL) {
    printf("usage: x N EXPR\n");
    return 0;
  }

  char *n_str = strtok(args, " ");
  char *expr_str = strtok(NULL, "");
  if (n_str == NULL || expr_str == NULL) {
    printf("usage: x N EXPR\n");
    return 0;
  }

  int n = strtol(n_str, NULL, 0);
  if (n <= 0) {
    printf("invalid number of times: %s\n", n_str);
    return 0;
  }

  bool success = false;
  vaddr_t addr = expr_eval(expr_str, &success);
  if (!success) {
    printf("expression evaluation failed: %s\n", parse_error_msg);
    return 0;
  }

  for (int i = 0; i < n; i++) {
    vaddr_t cur = addr + i * sizeof(word_t);
    word_t val = vaddr_read(cur, sizeof(word_t));
    printf(FMT_PADDR ": " FMT_WORD "\n", cur, val);
  }

  return 0;
}

static int cmd_p(char *args) {
  if (args == NULL) {
    printf("usage: p EXPR\n");
    return 0;
  }

  bool success = false;
  word_t val = expr_eval(args, &success);
  if (!success) {
    printf("expression evaluation failed: %s\n", parse_error_msg);
    return 0;
  }
  printf(FMT_WORD "\n", val);
  return 0;
}

static int cmd_w(char *args) {
  if (args == NULL) {
    printf("usage: w EXPR\n");
    return 0;
  }

  add_watchpoint(args);
  return 0;
}

static int cmd_d(char *args) {
  if (args == NULL) {
    printf("usage: d N\n");
    return 0;
  }

  int no = strtol(args, NULL, 0);
  delete_watchpoint(no);
  return 0;
}

static int cmd_help(char *args);

enum {
  CMD_HELP,
  CMD_C,
  CMD_Q,
  CMD_SI,
  CMD_INFO,
  CMD_X,
  CMD_P,
  CMD_W,
  CMD_D,
  NR_CMD,
};

static struct {
  const char *name;
  const char *description;
  int (*handler)(char *);
} cmd_table[NR_CMD] = {
    [CMD_HELP] = {"help", "Display information about all supported commands", cmd_help},
    [CMD_C] = {"c", "Continue the execution of the program", cmd_c},
    [CMD_Q] = {"q", "Exit NPC", cmd_q},
    [CMD_SI] = {"si", "Step one instruction", cmd_si}, // si [N]
    [CMD_INFO] = {"info", "Display information about the current state of the program", cmd_info},                         // info r, info w
    [CMD_X] = {"x", "View memory", cmd_x},           // x N EXPR
    [CMD_P] = {"p", "print expression", cmd_p},      // p EXPR
    [CMD_W] = {"w", "watchpoint expression", cmd_w}, // w EXPR
    [CMD_D] = {"d", "delete watchpoint", cmd_d},     // d N
};

static int cmd_help(char *args) {
  /* extract the first argument */
  char *arg = strtok(NULL, " ");
  int i;

  if (arg == NULL) {
    /* no argument given */
    for (i = 0; i < NR_CMD; i++) {
      printf("%s - %s\n", cmd_table[i].name, cmd_table[i].description);
    }
  } else {
    for (i = 0; i < NR_CMD; i++) {
      if (strcmp(arg, cmd_table[i].name) == 0) {
        printf("%s - %s\n", cmd_table[i].name, cmd_table[i].description);
        return 0;
      }
    }
    printf("Unknown command '%s'\n", arg);
  }
  return 0;
}

static int cmd_info(char *args) {
  if (args == NULL) {
    printf("%s - %s\n", cmd_table[CMD_INFO].name, cmd_table[CMD_INFO].description);
  } else if (0 == strcmp(args, "r")) {
    isa_reg_display();
  } else if (0 == strcmp(args, "w")) {
    list_watchpoints();
  } else {
    printf("Unknown subcommand '%s'\n", args);
    printf("%s - %s\n", cmd_table[CMD_INFO].name, cmd_table[CMD_INFO].description);
  }
  return 0;
}

void sdb_set_batch_mode() { is_batch_mode = true; }

void sdb_mainloop() {
  if (is_batch_mode) {
    cmd_c(NULL);
    return;
  }

  static char last_cmd[256] = "";  // 保存上一条命令
  char cmd_buf[256];               // 用于处理命令的缓冲区

  for (char *str; (str = rl_gets()) != NULL;) {
    /* 如果用户直接回车，使用上一条命令 */
    if (str[0] == '\0') {
      if (last_cmd[0] == '\0') {
        continue;  // 没有上一条命令，跳过
      }
      strncpy(cmd_buf, last_cmd, sizeof(cmd_buf) - 1);
      cmd_buf[sizeof(cmd_buf) - 1] = '\0';
    } else {
      strncpy(cmd_buf, str, sizeof(cmd_buf) - 1);
      cmd_buf[sizeof(cmd_buf) - 1] = '\0';
    }

    char *buf_end = cmd_buf + strlen(cmd_buf);

    /* extract the first token as the command */
    char *cmd = strtok(cmd_buf, " ");
    if (cmd == NULL) {
      continue;
    }

    /* treat the remaining string as the arguments,
     * which may need further parsing
     */
    char *args = cmd + strlen(cmd) + 1;
    if (args >= buf_end) {
      args = NULL;
    }

#ifdef CONFIG_DEVICE
    extern void sdl_clear_event_queue();
    sdl_clear_event_queue();
#endif

    int i;
    for (i = 0; i < NR_CMD; i++) {
      if (strcmp(cmd, cmd_table[i].name) == 0) {
        /* 保存有效命令（重新构造完整命令字符串） */
        if (args != NULL) {
          snprintf(last_cmd, sizeof(last_cmd), "%s %s", cmd, args);
        } else {
          strncpy(last_cmd, cmd, sizeof(last_cmd) - 1);
          last_cmd[sizeof(last_cmd) - 1] = '\0';
        }

        if (cmd_table[i].handler(args) < 0) {
          return;
        }
        break;
      }
    }

    if (i == NR_CMD) {
      printf("Unknown command '%s'\n", cmd);
    }
  }
}

void init_sdb() {
#ifdef CONFIG_WATCHPOINT
  /* Initialize the watchpoint pool. */
  extern void init_wp_pool();
  init_wp_pool();
#endif
}
