#pragma once
#include <cstdint>

class VerilatedContext;
class VNPCSoC;

VerilatedContext* vl_context_new();
void vl_context_delete(VerilatedContext* ctx);
void vl_context_command_args(VerilatedContext* ctx, int argc, const char** argv);
uint64_t vl_context_time(const VerilatedContext* ctx);
void vl_context_time_inc(VerilatedContext* ctx, uint64_t add);
bool vl_context_got_finish(const VerilatedContext* ctx);

VNPCSoC* vnpcsoc_new(VerilatedContext* ctx, const char* name);
void vnpcsoc_delete(VNPCSoC* top);
void vnpcsoc_eval(VNPCSoC* top);
void vnpcsoc_final(VNPCSoC* top);

void vnpcsoc_set_clock(VNPCSoC* top, uint8_t val);
void vnpcsoc_set_reset(VNPCSoC* top, uint8_t val);

uint8_t  vnpcsoc_get_probe_valid(const VNPCSoC* top);
uint8_t  vnpcsoc_get_probe_is_mmio(const VNPCSoC* top);
uint32_t vnpcsoc_get_probe_pc(const VNPCSoC* top);
uint32_t vnpcsoc_get_probe_dnpc(const VNPCSoC* top);
uint32_t vnpcsoc_get_probe_inst(const VNPCSoC* top);
uint32_t vnpcsoc_get_probe_gpr(const VNPCSoC* top, int index);
uint32_t vnpcsoc_get_probe_csr_mstatus(const VNPCSoC* top);
uint32_t vnpcsoc_get_probe_csr_mtvec(const VNPCSoC* top);
uint32_t vnpcsoc_get_probe_csr_mepc(const VNPCSoC* top);
uint32_t vnpcsoc_get_probe_csr_mcause(const VNPCSoC* top);
uint32_t vnpcsoc_get_probe_csr_mtval(const VNPCSoC* top);
uint32_t vnpcsoc_get_probe_csr_mvendorid(const VNPCSoC* top);
uint32_t vnpcsoc_get_probe_csr_marchid(const VNPCSoC* top);

uint32_t vnpcsoc_get_probe_perf_commit_cnt(const VNPCSoC* top);
uint32_t vnpcsoc_get_probe_perf_branch_cnt(const VNPCSoC* top);
uint32_t vnpcsoc_get_probe_perf_branch_mispredict_cnt(const VNPCSoC* top);

// FST trace
class VerilatedFstC;

void vl_trace_ever_on(bool flag);
VerilatedFstC* vl_fst_new();
void vl_fst_delete(VerilatedFstC* tfp);
void vl_fst_open(VerilatedFstC* tfp, const char* filename);
void vl_fst_close(VerilatedFstC* tfp);
void vl_fst_flush(VerilatedFstC* tfp);
void vl_fst_dump(VerilatedFstC* tfp, uint64_t time);
void vnpcsoc_trace(VNPCSoC* top, VerilatedFstC* tfp, int levels);
