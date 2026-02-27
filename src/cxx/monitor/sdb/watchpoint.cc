#include "sdb.hh"
#include <npc/state.hh>

#include <array>
#include <cstdio>
#include <cstring>
#include <string>
#include <vector>

namespace {

struct Watchpoint {
  int no;
  std::string expr;
  word_t last_value;
};

int next_no = 0;
std::vector<Watchpoint> watchpoints;

} // anonymous namespace

void init_wp_pool() {
  watchpoints.clear();
  next_no = 0;
}

int add_watchpoint(const char *expr_str) {
  auto val_opt = expr_eval(expr_str);
  if (!val_opt) {
    std::printf("expression evaluation failed, watchpoint not set: %s\n", expr_str);
    return -1;
  }

  int no = next_no++;
  watchpoints.push_back({no, std::string(expr_str), *val_opt});
  std::printf("watchpoint %d: %s\ncurrent value = " FMT_WORD "\n", no, expr_str, *val_opt);
  return no;
}

bool delete_watchpoint(int no) {
  for (auto it = watchpoints.begin(); it != watchpoints.end(); ++it) {
    if (it->no == no) {
      watchpoints.erase(it);
      std::printf("watchpoint %d deleted\n", no);
      return true;
    }
  }
  std::printf("watchpoint %d not found\n", no);
  return false;
}

void list_watchpoints() {
  if (watchpoints.empty()) {
    std::printf("no watchpoints\n");
    return;
  }
  std::printf("Num\tExpr\tValue\n");
  for (const auto &wp : watchpoints)
    std::printf("%d\t%s\t" FMT_WORD "\n", wp.no, wp.expr.c_str(), wp.last_value);
}

bool check_watchpoints() {
  bool triggered = false;
  for (auto &wp : watchpoints) {
    auto val_opt = expr_eval(wp.expr.c_str());
    if (!val_opt) {
      std::printf("watchpoint %d expression evaluation failed: %s\n",
                  wp.no, wp.expr.c_str());
      continue;
    }
    if (*val_opt != wp.last_value) {
      std::printf("watchpoint %d triggered: %s\n", wp.no, wp.expr.c_str());
      std::printf("old value = " FMT_WORD ", new value = " FMT_WORD "\n",
                  wp.last_value, *val_opt);
      wp.last_value = *val_opt;
      triggered = true;
    }
  }
  if (triggered && npc_state.state == NPC_RUNNING)
    npc_state.state = NPC_STOP;
  return triggered;
}
