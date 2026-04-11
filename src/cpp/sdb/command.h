#ifndef NPC_SDB_COMMAND_H_
#define NPC_SDB_COMMAND_H_

#include <optional>
#include <string>

#include "absl/strings/string_view.h"

namespace npc {

struct Command {
  std::string name;
  std::string args;
};

std::optional<Command> ParseCommand(absl::string_view input);

}  // namespace npc

#endif  // NPC_SDB_COMMAND_H_
