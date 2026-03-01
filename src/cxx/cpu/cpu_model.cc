#include <npc/cpu_model.hh>
#include <cassert>

namespace npc {

static CpuModel *g_dut = nullptr;
static CpuModel *g_ref = nullptr;

CpuModel &dut() {
  assert(g_dut && "DUT model not set");
  return *g_dut;
}

void set_dut(CpuModel *model) { g_dut = model; }

CpuModel &ref() {
  assert(g_ref && "REF model not set");
  return *g_ref;
}

void set_ref(CpuModel *model) { g_ref = model; }

} // namespace npc
