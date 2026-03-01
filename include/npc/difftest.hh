#pragma once

#include <npc/common.hh>

enum { DIFFTEST_TO_DUT, DIFFTEST_TO_REF };

namespace npc {
class CpuModel;
struct StepResult;

class Scoreboard {
public:
  bool check(CpuModel &dut, CpuModel &ref, const StepResult &dut_result);
};
} // namespace npc

#ifdef CONFIG_DIFFTEST
void init_difftest(long img_size, int port);
#else
inline void init_difftest(long, int) {}
#endif
