#include "spike_bridge.h"
#include "sim.h"
#include "mmu.h"
#include <vector>
#include <string>
#include <utility>

// ============ sim_t ============

sim_t* sim_new(const char* isa, const uint64_t* mem_bases,
               const uint64_t* mem_sizes, int mem_count) {
    std::vector<std::pair<reg_t, mem_t*>> mems;
    for (int i = 0; i < mem_count; i++) {
        size_t aligned = (mem_sizes[i] + 0xFFF) & ~0xFFF; // 4k aligned
        mems.emplace_back(reg_t(mem_bases[i]), new mem_t(aligned));
    }

    static debug_module_config_t dm_config = { 2, 0, false, 0, true, true, true, true, true };

    auto* cfg = new cfg_t(
        std::make_pair(reg_t(0), reg_t(0)),
        nullptr, isa, DEFAULT_PRIV, DEFAULT_VARCH,
        false, endianness_little, 16,
        std::vector<mem_cfg_t>(),
        std::vector<size_t>(1),
        false, 4);

    std::vector<std::pair<reg_t, abstract_device_t*>> plugin_devices;
    std::vector<std::string> htif_args{""};

    return new sim_t(cfg, false,
        mems, plugin_devices, htif_args,
        dm_config, nullptr, false, nullptr,
        false, nullptr, true);
}

void sim_delete(sim_t* sim) { delete sim; }
int sim_run(sim_t* sim) { return sim->run(); }
void sim_set_debug(sim_t* sim, bool value) { sim->set_debug(value); }
void sim_set_histogram(sim_t* sim, bool value) { sim->set_histogram(value); }
void sim_configure_log(sim_t* sim, bool enable_log, bool enable_commitlog) { sim->configure_log(enable_log, enable_commitlog); }
void sim_set_procs_debug(sim_t* sim, bool value) { sim->set_procs_debug(value); }
const char* sim_get_dts(sim_t* sim) { return sim->get_dts(); }
processor_t* sim_get_core(sim_t* sim, int index) { return sim->get_core(index); }

// ============ processor_t ============

void proc_step(processor_t* proc, uint64_t n) { proc->step(n); }
void proc_reset(processor_t* proc) { proc->reset(); }
void proc_set_debug(processor_t* proc, bool value) { proc->set_debug(value); }
uint32_t proc_get_id(const processor_t* proc) { return proc->get_id(); }
unsigned proc_get_xlen(const processor_t* proc) { return proc->get_xlen(); }
state_t* proc_get_state(processor_t* proc) { return proc->get_state(); }
mmu_t* proc_get_mmu(processor_t* proc) { return proc->get_mmu(); }
uint64_t proc_get_csr(processor_t* proc, int which) { return proc->get_csr(which); }
void proc_put_csr(processor_t* proc, int which, uint64_t val) { proc->put_csr(which, val); }
void proc_take_trap(processor_t* proc, uint64_t cause, uint64_t tval) {
    insn_trap_t t(cause, false, tval);
    proc->take_trap_public(t, proc->get_state()->pc);
}

// ============ state_t ============

uint64_t state_get_pc(const state_t* st) { return st->pc; }
void state_set_pc(state_t* st, uint64_t pc) { st->pc = pc; }
uint64_t state_get_gpr(const state_t* st, int index) { return st->XPR[index]; }
void state_set_gpr(state_t* st, int index, uint64_t val) { st->XPR.write(index, val); }

// ============ mmu_t ============

uint8_t  mmu_load_u8(mmu_t* mmu, uint64_t addr)  { return mmu->load<uint8_t>(addr); }
uint16_t mmu_load_u16(mmu_t* mmu, uint64_t addr) { return mmu->load<uint16_t>(addr); }
uint32_t mmu_load_u32(mmu_t* mmu, uint64_t addr) { return mmu->load<uint32_t>(addr); }

void mmu_store_u8(mmu_t* mmu, uint64_t addr, uint8_t val)   { mmu->store<uint8_t>(addr, val); }
void mmu_store_u16(mmu_t* mmu, uint64_t addr, uint16_t val) { mmu->store<uint16_t>(addr, val); }
void mmu_store_u32(mmu_t* mmu, uint64_t addr, uint32_t val) { mmu->store<uint32_t>(addr, val); }
