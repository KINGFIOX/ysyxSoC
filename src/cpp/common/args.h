#ifndef NPC_COMMON_ARGS_H_
#define NPC_COMMON_ARGS_H_

#include <string>

#include "absl/flags/declare.h"

ABSL_DECLARE_FLAG(bool, batch);
ABSL_DECLARE_FLAG(bool, nvboard);
ABSL_DECLARE_FLAG(bool, wave);
ABSL_DECLARE_FLAG(bool, enable_fork);
ABSL_DECLARE_FLAG(std::string, image);
ABSL_DECLARE_FLAG(std::string, log);

#endif  // NPC_COMMON_ARGS_H_
