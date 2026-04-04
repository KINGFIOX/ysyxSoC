/// DPI-C callback functions called by Verilator during simulation.
///
/// These must match the `import "DPI-C"` declarations in the Verilog/SystemVerilog
/// sources exactly. Verilator resolves them at link time.
///
/// All addresses passed from RTL are device-relative offsets (base already stripped).
use crate::libcpu::abstract_cpu::AbstractCpu;
use crate::libcpu::spike::SpikeCpu;
use crate::libdpi::globals::{DPI_FLASH, DPI_MROM, DPI_PSRAM, DPI_SDRAM};

#[unsafe(no_mangle)]
pub extern "C" fn mrom_read(addr: i32, data: *mut i32) {
    DPI_MROM.with(|mrom| {
        let offset = addr as u32 as usize;
        let b0 = mrom[offset] as u32;
        let b1 = mrom[offset + 1] as u32;
        let b2 = mrom[offset + 2] as u32;
        let b3 = mrom[offset + 3] as u32;
        unsafe { *data = (b0 | (b1 << 8) | (b2 << 16) | (b3 << 24)) as i32 };
    });
}

#[unsafe(no_mangle)]
pub extern "C" fn flash_read(addr: i32, data: *mut i8) {
    DPI_FLASH.with(|flash| {
        unsafe { *data = flash[addr as u32 as usize] as i8 };
    });
}

#[unsafe(no_mangle)]
pub extern "C" fn psram_read(addr: i32, data: *mut i8) {
    DPI_PSRAM.with(|psram| {
        unsafe { *data = psram[addr as u32 as usize] as i8 };
    });
}

#[unsafe(no_mangle)]
pub extern "C" fn psram_write(addr: i32, data: i8) {
    DPI_PSRAM.with_mut(|psram| {
        psram[addr as u32 as usize] = data as u8;
    });
}

#[unsafe(no_mangle)]
pub extern "C" fn sdram_read(addr: i32, data: *mut i16) {
    DPI_SDRAM.with(|sdram| {
        let offset = addr as u32 as usize;
        let lo = sdram[offset] as u16;
        let hi = sdram[offset + 1] as u16;
        unsafe { *data = (lo | (hi << 8)) as i16 };
    });
}

#[allow(unused)]
use log::info;

#[unsafe(no_mangle)]
pub extern "C" fn sdram_write(addr: i32, data: u8) {
    DPI_SDRAM.with_mut(|sdram| {
        sdram[addr as u32 as usize] = data;
    });
}

// ============ Spike Frontend (UInt(64.W) as WYSIWYG chandle) ============
//
// RawClockedNonVoidFunctionCall convention: inputs first, output pointer last.

#[unsafe(no_mangle)]
pub extern "C" fn spike_fe_new(out: *mut i64) {
    let flash_data = DPI_FLASH.with(|flash| flash.clone());
    let spike = SpikeCpu::new(&flash_data);
    unsafe { *out = Box::into_raw(Box::new(spike)) as i64 };
}

/// Returns packed {npc[63:32], inst[31:0]}. All-ones (u64::MAX) on failure.
#[unsafe(no_mangle)]
pub extern "C" fn spike_fe_fetch_and_step(handle: i64, out: *mut i64) {
    let spike = unsafe { &mut *(handle as *mut SpikeCpu) };
    let pc = spike.pc();
    let inst = match spike.mem_load(pc, 4) {
        Ok(v) => v,
        Err(_) => {
            unsafe { *out = -1 };
            return;
        }
    };
    if spike.step().is_err() {
        unsafe { *out = -1 };
        return;
    }
    let npc = spike.pc();
    unsafe { *out = ((npc as u64) << 32 | (inst as u64)) as i64 };
}

#[unsafe(no_mangle)]
pub extern "C" fn spike_fe_set_gpr(handle: i64, idx: i32, val: i32) {
    let spike = unsafe { &mut *(handle as *mut SpikeCpu) };
    spike.set_gpr(idx as usize, val as u32).unwrap();
}

#[unsafe(no_mangle)]
pub extern "C" fn spike_fe_set_pc(handle: i64, pc: i32) {
    let spike = unsafe { &mut *(handle as *mut SpikeCpu) };
    spike.set_pc(pc as u32).unwrap();
}
