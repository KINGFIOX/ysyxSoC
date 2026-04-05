/// DPI-C callback functions called by Verilator during simulation.
///
/// These must match the `import "DPI-C"` declarations in the Verilog/SystemVerilog
/// sources exactly. Verilator resolves them at link time.
///
/// All addresses passed from RTL are device-relative offsets (base already stripped).
use crate::libcpu::abstract_cpu::AbstractCpu;
use crate::libcpu::spike::SpikeCpu;
use crate::libdpi::globals::{DPI_FLASH, DPI_MROM, DPI_SDRAM};

#[unsafe(no_mangle)]
pub extern "C" fn mrom_read(addr: i64, data: *mut i32) {
    DPI_MROM.with(|mrom| {
        let offset = addr as u64 as usize;
        let b0 = mrom[offset] as u32;
        let b1 = mrom[offset + 1] as u32;
        let b2 = mrom[offset + 2] as u32;
        let b3 = mrom[offset + 3] as u32;
        unsafe { *data = (b0 | (b1 << 8) | (b2 << 16) | (b3 << 24)) as i32 };
    });
}

#[unsafe(no_mangle)]
pub extern "C" fn flash_read(addr: i64, data: *mut i8) {
    DPI_FLASH.with(|flash| {
        unsafe { *data = flash[addr as u64 as usize] as i8 };
    });
}

#[unsafe(no_mangle)]
pub extern "C" fn sdram_read(addr: i64, data: *mut i16) {
    DPI_SDRAM.with(|sdram| {
        let offset = addr as u64 as usize;
        let lo = sdram[offset] as u16;
        let hi = sdram[offset + 1] as u16;
        unsafe { *data = (lo | (hi << 8)) as i16 };
    });
}

#[allow(unused)]
use log::info;

#[unsafe(no_mangle)]
pub extern "C" fn sdram_write(addr: i64, data: u8) {
    DPI_SDRAM.with_mut(|sdram| {
        sdram[addr as u64 as usize] = data;
    });
}

// ============ Spike Frontend (DPI chandle) ============
//
// RawClockedNonVoidFunctionCall convention: inputs first, output pointer last.

#[unsafe(no_mangle)]
pub extern "C" fn spike_fe_new(out: *mut i64) {
    let flash_data = DPI_FLASH.with(|flash| flash.clone());
    let spike = SpikeCpu::new(&flash_data);
    unsafe { *out = Box::into_raw(Box::new(spike)) as i64 };
}

/// Fetch instruction at current PC, step spike, return result as a 128-bit packed struct.
/// Layout (UInt(128.W)): bits[31:0]=ok, bits[63:32]=inst, bits[127:64]=npc
/// DPI output: svBitVecVal[4] (uint32_t[4]), little-endian word order.
#[unsafe(no_mangle)]
pub extern "C" fn spike_fe_fetch_and_step(handle: i64, out: *mut u32) {
    let spike = unsafe { &mut *(handle as *mut SpikeCpu) };
    let pc = spike.pc();
    let inst = match spike.mem_load(pc, 4) {
        Ok(v) => v as u32,
        Err(_) => {
            unsafe {
                *out = 0; // ok = 0
                *out.add(1) = 0;
                *out.add(2) = 0;
                *out.add(3) = 0;
            }
            return;
        }
    };
    if spike.step().is_err() {
        unsafe {
            *out = 0;
            *out.add(1) = 0;
            *out.add(2) = 0;
            *out.add(3) = 0;
        }
        return;
    }
    let npc = spike.pc();
    unsafe {
        *out = 1; // ok
        *out.add(1) = inst;
        *out.add(2) = npc as u32;
        *out.add(3) = (npc >> 32) as u32;
    }
}

#[unsafe(no_mangle)]
pub extern "C" fn spike_fe_set_gpr(handle: i64, idx: i32, val: i64) {
    let spike = unsafe { &mut *(handle as *mut SpikeCpu) };
    spike.set_gpr(idx as usize, val as u64).unwrap();
}

#[unsafe(no_mangle)]
pub extern "C" fn spike_fe_set_pc(handle: i64, pc: i64) {
    let spike = unsafe { &mut *(handle as *mut SpikeCpu) };
    spike.set_pc(pc as u64).unwrap();
}
