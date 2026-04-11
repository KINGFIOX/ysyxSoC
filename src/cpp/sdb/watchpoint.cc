#include "sdb/watchpoint.h"

#include <algorithm>

#include "absl/strings/str_format.h"
#include "sdb/expr.h"

namespace npc {

absl::StatusOr<int> WatchpointPool::add(const std::string& expr,
                                        const AbstractCpu& cpu) {
  auto val = ExprEval(expr, cpu);
  if (!val.ok()) {
    return val.status();
  }

  int id = next_id_++;
  watchpoints_.push_back(
      Watchpoint{.id = id, .expr = expr, .last_value = *val});
  return id;
}

bool WatchpointPool::remove(int id) {
  auto it = std::remove_if(watchpoints_.begin(), watchpoints_.end(),
                           [id](const Watchpoint& wp) { return wp.id == id; });
  if (it == watchpoints_.end()) {
    return false;
  }
  watchpoints_.erase(it, watchpoints_.end());
  return true;
}

bool WatchpointPool::check(const AbstractCpu& cpu, std::string& out) {
  bool triggered = false;
  for (auto& wp : watchpoints_) {
    auto val = ExprEval(wp.expr, cpu);
    if (!val.ok()) {
      continue;
    }
    if (*val != wp.last_value) {
      absl::StrAppendFormat(&out,
                            "watchpoint #%d: %s\n  old = 0x%016x\n  "
                            "new = 0x%016x\n",
                            wp.id, wp.expr, wp.last_value, *val);
      wp.last_value = *val;
      triggered = true;
    }
  }
  return triggered;
}

void WatchpointPool::list(std::string& out) const {
  if (watchpoints_.empty()) {
    out += "no watchpoints\n";
    return;
  }
  for (const auto& wp : watchpoints_) {
    absl::StrAppendFormat(&out, "  #%d: %s = 0x%016x\n", wp.id, wp.expr,
                          wp.last_value);
  }
}

}  // namespace npc
