#[allow(ambiguous_glob_reexports)]
pub use verilator::*;
#[allow(ambiguous_glob_reexports)]
pub use spike::*;
#[allow(ambiguous_glob_reexports)]
pub use nvboard::*;

use autocxx::prelude::*;

include_cpp! {
    #include "verilator_bridge.h"
    name!(verilator)
    safety!(unsafe)
    generate!("vl_stop_triggered")
    generate!("vl_stop_clear")
    generate!("vl_context_new")
    generate!("vl_context_delete")
    // vl_context_command_args has const char** which autocxx can't handle;
    // use the raw extern "C" below if needed.
    generate!("vl_context_time")
    generate!("vl_context_time_inc")
    generate!("vl_context_got_finish")
    generate!("vnpcsoc_new")
    generate!("vnpcsoc_delete")
    generate!("vnpcsoc_eval")
    generate!("vnpcsoc_final")
    generate!("vnpcsoc_set_clock")
    generate!("vnpcsoc_set_reset")
    generate!("vnpcsoc_get_probe_valid")
    generate!("vnpcsoc_get_probe_is_mmio")
    generate!("vnpcsoc_get_probe_pc")
    generate!("vnpcsoc_get_probe_dnpc")
    generate!("vnpcsoc_get_probe_inst")
    generate!("vnpcsoc_get_probe_gpr")
    generate!("vnpcsoc_get_probe_csr_mstatus")
    generate!("vnpcsoc_get_probe_csr_mtvec")
    generate!("vnpcsoc_get_probe_csr_mepc")
    generate!("vnpcsoc_get_probe_csr_mcause")
    generate!("vnpcsoc_get_probe_csr_mtval")
    generate!("vnpcsoc_get_probe_csr_mvendorid")
    generate!("vnpcsoc_get_probe_csr_marchid")
    generate!("vnpcsoc_get_probe_perf_commit_cnt")
    generate!("vnpcsoc_get_probe_perf_branch_cnt")
    generate!("vnpcsoc_get_probe_perf_branch_mispredict_cnt")
    generate!("vnpcsoc_get_probe_perf_flush_cnt")
    generate!("vl_trace_ever_on")
    generate!("vl_fst_new")
    generate!("vl_fst_delete")
    generate!("vl_fst_open")
    generate!("vl_fst_close")
    generate!("vl_fst_flush")
    generate!("vl_fst_dump")
    generate!("vnpcsoc_trace")
}

include_cpp! {
    #include "nvboard_bridge.h"
    name!(nvboard)
    safety!(unsafe)
    generate!("nvboard_bridge_init")
    generate!("nvboard_bridge_quit")
    generate!("nvboard_bridge_update")
}

include_cpp! {
    #include "spike_bridge.h"
    name!(spike)
    safety!(unsafe)
    // sim_t
    generate!("sim_new")
    generate!("sim_delete")
    generate!("sim_run")
    generate!("sim_set_debug")
    generate!("sim_set_histogram")
    generate!("sim_configure_log")
    generate!("sim_set_procs_debug")
    generate!("sim_get_dts")
    generate!("sim_get_core")
    // processor_t
    generate!("proc_step")
    generate!("proc_reset")
    generate!("proc_set_debug")
    generate!("proc_get_id")
    generate!("proc_get_xlen")
    generate!("proc_get_state")
    generate!("proc_get_mmu")
    generate!("proc_get_csr")
    generate!("proc_put_csr")
    generate!("proc_take_trap")
    // state_t
    generate!("state_get_pc")
    generate!("state_set_pc")
    generate!("state_get_gpr")
    generate!("state_set_gpr")
    // mmu_t
    generate!("mmu_load_u8")
    generate!("mmu_load_u16")
    generate!("mmu_load_u32")
    generate!("mmu_store_u8")
    generate!("mmu_store_u16")
    generate!("mmu_store_u32")
}
