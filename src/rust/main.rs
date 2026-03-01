mod dpi;

use npc::ffi::*;

#[allow(unused)]
use npc::libcpu::*;

use std::ffi::CString;

fn main() {
    let ctx = vl_context_new();
    let name = CString::new("TOP").unwrap();
    let top = unsafe { vnpcsoc_new(ctx, name.as_ptr()) };

    // VCD trace setup
    vl_trace_ever_on(true);
    let tfp = vl_vcd_new();
    unsafe { vnpcsoc_trace(top, tfp, autocxx::c_int(99)) };
    let vcd_path = CString::new("build/npc_core.vcd").unwrap();
    unsafe { vl_vcd_open(tfp, vcd_path.as_ptr()) };
    let mut sim_time: u64 = 0;

    let tick = |top: *mut VNPCSoC, tfp: *mut VerilatedVcdC, t: &mut u64| unsafe {
        vnpcsoc_set_clock(top, 0);
        vnpcsoc_eval(top);
        vl_vcd_dump(tfp, *t);
        *t += 1;

        vnpcsoc_set_clock(top, 1);
        vnpcsoc_eval(top);
        vl_vcd_dump(tfp, *t);
        *t += 1;
    };

    // Reset
    unsafe { vnpcsoc_set_reset(top, 1) };
    for _ in 0..15 {
        tick(top, tfp, &mut sim_time);
    }
    unsafe { vnpcsoc_set_reset(top, 0) };

    // Run
    unsafe { vnpcsoc_set_step(top, 1) };
    for i in 0..10 {
        tick(top, tfp, &mut sim_time);

        if unsafe { vnpcsoc_get_debug_valid(top) } != 0 {
            let pc = unsafe { vnpcsoc_get_debug_pc(top) };
            let inst = unsafe { vnpcsoc_get_debug_inst(top) };
            println!("cycle {i}: pc=0x{pc:08x} inst=0x{inst:08x}");
        }
    }

    // GPR dump
    println!("\n--- GPR dump ---");
    for i in 0..32 {
        let val = unsafe { vnpcsoc_get_debug_gpr(top, i.into()) };
        print!("x{i:02}=0x{val:08x}  ");
        if (i + 1) % 4 == 0 {
            println!();
        }
    }

    // Cleanup
    unsafe {
        vl_vcd_flush(tfp);
        vl_vcd_close(tfp);
        vl_vcd_delete(tfp);
        vnpcsoc_delete(top);
        vl_context_delete(ctx);
    }
    println!("\nVCD written to build/npc_core.vcd");
}
