#ifndef NPC_SDB_WATCHPOINT_H_
#define NPC_SDB_WATCHPOINT_H_

#include <cstdint>
#include <string>
#include <vector>

#include "absl/status/statusor.h"
#include "cpu/abstract_cpu.h"

namespace npc {

struct Watchpoint {
  int id;
  std::string expr;
  uint64_t last_value;
};

class WatchpointPool {
 public:
  WatchpointPool() = default;

  absl::StatusOr<int> add(const std::string& expr, const AbstractCpu& cpu);
  bool remove(int id);

  // Check all watchpoints. Returns true if any watchpoint triggered.
  // Appends messages about triggered watchpoints to `out`.
  bool check(const AbstractCpu& cpu, std::string& out);

  void list(std::string& out) const;

 private:
  std::vector<Watchpoint> watchpoints_;
  int next_id_ = 1;
};

}  // namespace npc

#endif  // NPC_SDB_WATCHPOINT_H_
