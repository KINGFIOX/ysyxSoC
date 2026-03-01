#pragma once
#include <cstdint>
#include <cstddef>

class sim_t;
class processor_t;
struct state_t;
class mmu_t;

// ============ sim_t ============

sim_t* sim_new(const char* isa, const uint64_t* mem_bases, const uint64_t* mem_sizes, int mem_count);
void sim_delete(sim_t* sim);
int sim_run(sim_t* sim);
void sim_set_debug(sim_t* sim, bool value);
void sim_set_histogram(sim_t* sim, bool value);
void sim_configure_log(sim_t* sim, bool enable_log, bool enable_commitlog);
void sim_set_procs_debug(sim_t* sim, bool value);
const char* sim_get_dts(sim_t* sim);
processor_t* sim_get_core(sim_t* sim, int index);

// ============ processor_t ============

void proc_step(processor_t* proc, uint64_t n);
void proc_reset(processor_t* proc);
void proc_set_debug(processor_t* proc, bool value);
uint32_t proc_get_id(const processor_t* proc);
unsigned proc_get_xlen(const processor_t* proc);
state_t* proc_get_state(processor_t* proc);
mmu_t* proc_get_mmu(processor_t* proc);
uint64_t proc_get_csr(processor_t* proc, int which);
void proc_put_csr(processor_t* proc, int which, uint64_t val);
void proc_take_trap(processor_t* proc, uint64_t cause, uint64_t tval);

// ============ state_t ============

uint64_t state_get_pc(const state_t* st);
void state_set_pc(state_t* st, uint64_t pc);
uint64_t state_get_gpr(const state_t* st, int index);
void state_set_gpr(state_t* st, int index, uint64_t val);

// ============ mmu_t ============

uint8_t  mmu_load_u8(mmu_t* mmu, uint64_t addr);
uint16_t mmu_load_u16(mmu_t* mmu, uint64_t addr);
uint32_t mmu_load_u32(mmu_t* mmu, uint64_t addr);
void mmu_store_u8(mmu_t* mmu, uint64_t addr, uint8_t val);
void mmu_store_u16(mmu_t* mmu, uint64_t addr, uint16_t val);
void mmu_store_u32(mmu_t* mmu, uint64_t addr, uint32_t val);
