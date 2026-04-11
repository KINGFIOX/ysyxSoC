#include "sdb/expr.h"

#include <string>

#include "absl/strings/str_format.h"

// Declared in expr.y
extern uint64_t expr_eval(const char* expr_str,
                           const npc::AbstractCpu* cpu, bool* success);
extern const char* expr_parse_error_msg;

namespace npc {

absl::StatusOr<uint64_t> ExprEval(absl::string_view expr,
                                   const AbstractCpu& cpu) {
  std::string expr_str(expr);
  bool success = false;
  uint64_t result = expr_eval(expr_str.c_str(), &cpu, &success);
  if (!success) {
    const char* msg =
        expr_parse_error_msg ? expr_parse_error_msg : "parse error";
    return absl::InvalidArgumentError(
        absl::StrFormat("expression error: %s", msg));
  }
  return result;
}

}  // namespace npc
