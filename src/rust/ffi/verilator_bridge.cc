#include "verilator_bridge.h"
#include "VNPCSoC.h"
#include "VNPCSoC___024root.h"
#include "verilated.h"
#include "verilated_vcd_c.h"

// VerilatedContext

VerilatedContext* vl_context_new() { return new VerilatedContext; }
void vl_context_delete(VerilatedContext* ctx) { delete ctx; }
void vl_context_command_args(VerilatedContext* ctx, int argc, const char** argv) { ctx->commandArgs(argc, argv); }
uint64_t vl_context_time(const VerilatedContext* ctx) { return ctx->time(); }
void vl_context_time_inc(VerilatedContext* ctx, uint64_t add) { ctx->timeInc(add); }
bool vl_context_got_finish(const VerilatedContext* ctx) { return ctx->gotFinish(); }

// VNPCSoC

VNPCSoC* vnpcsoc_new(VerilatedContext* ctx, const char* name) { return new VNPCSoC(ctx, name); }
void vnpcsoc_delete(VNPCSoC* top) { top->final(); delete top; }
void vnpcsoc_eval(VNPCSoC* top) { top->eval(); }
void vnpcsoc_final(VNPCSoC* top) { top->final(); }

void vnpcsoc_set_clock(VNPCSoC* top, uint8_t val) { top->clock = val; }
void vnpcsoc_set_reset(VNPCSoC* top, uint8_t val) { top->reset = val; }

uint8_t  vnpcsoc_get_debug_valid(const VNPCSoC* top)  { return top->debug_valid; }
uint8_t  vnpcsoc_get_debug_isMMIO(const VNPCSoC* top)  { return top->debug_isMMIO; }
uint32_t vnpcsoc_get_debug_pc(const VNPCSoC* top)      { return top->debug_pc; }
uint32_t vnpcsoc_get_debug_dnpc(const VNPCSoC* top)    { return top->debug_dnpc; }
uint32_t vnpcsoc_get_debug_inst(const VNPCSoC* top)    { return top->debug_inst; }
uint32_t vnpcsoc_get_debug_gpr(const VNPCSoC* top, int index) {
    switch (index) {
        case  0: return top->debug_gpr_0;  case  1: return top->debug_gpr_1;
        case  2: return top->debug_gpr_2;  case  3: return top->debug_gpr_3;
        case  4: return top->debug_gpr_4;  case  5: return top->debug_gpr_5; // NOLINT
        case  6: return top->debug_gpr_6;  case  7: return top->debug_gpr_7; // NOLINT
        case  8: return top->debug_gpr_8;  case  9: return top->debug_gpr_9; // NOLINT
        case 10: return top->debug_gpr_10; case 11: return top->debug_gpr_11; // NOLINT
        case 12: return top->debug_gpr_12; case 13: return top->debug_gpr_13; // NOLINT
        case 14: return top->debug_gpr_14; case 15: return top->debug_gpr_15; // NOLINT
        case 16: return top->debug_gpr_16; case 17: return top->debug_gpr_17; // NOLINT
        case 18: return top->debug_gpr_18; case 19: return top->debug_gpr_19; // NOLINT
        case 20: return top->debug_gpr_20; case 21: return top->debug_gpr_21; // NOLINT
        case 22: return top->debug_gpr_22; case 23: return top->debug_gpr_23; // NOLINT
        case 24: return top->debug_gpr_24; case 25: return top->debug_gpr_25; // NOLINT
        case 26: return top->debug_gpr_26; case 27: return top->debug_gpr_27; // NOLINT
        case 28: return top->debug_gpr_28; case 29: return top->debug_gpr_29; // NOLINT
        case 30: return top->debug_gpr_30; case 31: return top->debug_gpr_31; // NOLINT
        default: return 0;
    }
}
uint32_t vnpcsoc_get_debug_csr_mstatus(const VNPCSoC* top)   { return top->debug_csr_mstatus; }
uint32_t vnpcsoc_get_debug_csr_mtvec(const VNPCSoC* top)     { return top->debug_csr_mtvec; }
uint32_t vnpcsoc_get_debug_csr_mepc(const VNPCSoC* top)      { return top->debug_csr_mepc; }
uint32_t vnpcsoc_get_debug_csr_mcause(const VNPCSoC* top)    { return top->debug_csr_mcause; }
uint32_t vnpcsoc_get_debug_csr_mtval(const VNPCSoC* top)     { return top->debug_csr_mtval; }
uint32_t vnpcsoc_get_debug_csr_mvendorid(const VNPCSoC* top) { return top->debug_csr_mvendorid; }
uint32_t vnpcsoc_get_debug_csr_marchid(const VNPCSoC* top)   { return top->debug_csr_marchid; }

// VCD trace

void vl_trace_ever_on(bool flag) { Verilated::traceEverOn(flag); }
VerilatedVcdC* vl_vcd_new() { return new VerilatedVcdC; }
void vl_vcd_delete(VerilatedVcdC* tfp) { delete tfp; }
void vl_vcd_open(VerilatedVcdC* tfp, const char* filename) { tfp->open(filename); }
void vl_vcd_close(VerilatedVcdC* tfp) { tfp->close(); }
void vl_vcd_flush(VerilatedVcdC* tfp) { tfp->flush(); }
void vl_vcd_dump(VerilatedVcdC* tfp, uint64_t time) { tfp->dump(time); }
void vnpcsoc_trace(VNPCSoC* top, VerilatedVcdC* tfp, int levels) { top->trace(tfp, levels); }
