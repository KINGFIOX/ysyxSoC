/// DPI-C callback functions called by Verilator during simulation.
///
/// These must match the `import "DPI-C"` declarations in the Verilog/SystemVerilog
/// sources exactly. Verilator resolves them at link time.
///
/// All addresses passed from RTL are device-relative offsets (base already stripped).

use npc::libcpu::verilator::globals;

#[unsafe(no_mangle)]
pub extern "C" fn exception_dpi(_en: i32, _pc: i32, _mcause: i32, _a0: i32, _tval: i32) {
    // TODO: implement exception handling
}

#[unsafe(no_mangle)]
pub extern "C" fn mrom_read(addr: i32, data: *mut i32) {
    let offset = addr as u32;
    let b0 = globals::mrom_read(offset) as u32;
    let b1 = globals::mrom_read(offset + 1) as u32;
    let b2 = globals::mrom_read(offset + 2) as u32;
    let b3 = globals::mrom_read(offset + 3) as u32;
    unsafe { *data = (b0 | (b1 << 8) | (b2 << 16) | (b3 << 24)) as i32 };
}

#[unsafe(no_mangle)]
pub extern "C" fn flash_read(addr: i32, data: *mut i8) {
    unsafe { *data = globals::flash_read(addr as u32) as i8 };
}

#[unsafe(no_mangle)]
pub extern "C" fn psram_read(addr: i32, data: *mut i8) {
    unsafe { *data = globals::psram_read(addr as u32) as i8 };
}

#[unsafe(no_mangle)]
pub extern "C" fn psram_write(addr: i32, data: i8) {
    globals::psram_write(addr as u32, data as u8);
}

#[unsafe(no_mangle)]
pub extern "C" fn sdram_read_dpic(addr: i32) -> u16 {
    let offset = addr as u32;
    let lo = globals::sdram_read(offset) as u16;
    let hi = globals::sdram_read(offset + 1) as u16;
    lo | (hi << 8)
}

#[unsafe(no_mangle)]
pub extern "C" fn sdram_write(addr: i32, data: u8) {
    globals::sdram_write(addr as u32, data);
}
