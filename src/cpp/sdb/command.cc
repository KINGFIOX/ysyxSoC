#include "sdb/command.h"

#include "absl/strings/ascii.h"
#include "absl/strings/string_view.h"

namespace npc {

std::optional<Command> ParseCommand(absl::string_view input) {
  input = absl::StripAsciiWhitespace(input);
  if (input.empty()) return std::nullopt;

  size_t space = input.find_first_of(" \t");
  if (space == absl::string_view::npos) {
    return Command{std::string(input), ""};
  }
  std::string name(input.substr(0, space));
  absl::string_view rest = input.substr(space);
  rest = absl::StripLeadingAsciiWhitespace(rest);
  return Command{std::move(name), std::string(rest)};
}

}  // namespace npc
