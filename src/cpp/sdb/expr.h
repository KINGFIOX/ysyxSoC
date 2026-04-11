#ifndef NPC_SDB_EXPR_H_
#define NPC_SDB_EXPR_H_

#include <cstdint>

#include "absl/status/statusor.h"
#include "absl/strings/string_view.h"
#include "cpu/abstract_cpu.h"

namespace npc {

// Evaluate an expression string in the context of a CPU.
// Supports: decimal, hex (0x...), $register, arithmetic (+,-,*,/),
// comparison (==,!=,<,<=,>,>=), logical (&&,||), unary (-), deref (*addr).
absl::StatusOr<uint64_t> ExprEval(absl::string_view expr,
                                   const AbstractCpu& cpu);

}  // namespace npc

#endif  // NPC_SDB_EXPR_H_
