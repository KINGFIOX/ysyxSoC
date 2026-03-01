/// DPI-C callback functions called by Verilator during simulation.
///
/// These must match the `import "DPI-C"` declarations in the Verilog/SystemVerilog
/// sources exactly. Verilator resolves them at link time.

#[unsafe(no_mangle)]
pub extern "C" fn exception_dpi(_en: i32, _pc: i32, _mcause: i32, _a0: i32, _tval: i32) {
    // TODO: implement exception handling
}

#[unsafe(no_mangle)]
pub extern "C" fn mrom_read(_addr: i32, _data: *mut i32) {
    // TODO: implement MROM read
}

#[unsafe(no_mangle)]
pub extern "C" fn flash_read(_addr: i32, _data: *mut i8) {
    // TODO: implement Flash read
}

#[unsafe(no_mangle)]
pub extern "C" fn psram_read(_addr: i32, _data: *mut i8) {
    // TODO: implement PSRAM read
}

#[unsafe(no_mangle)]
pub extern "C" fn psram_write(_addr: i32, _data: i8) {
    // TODO: implement PSRAM write
}

#[unsafe(no_mangle)]
pub extern "C" fn sdram_read_dpic(_addr: i32) -> u16 {
    // TODO: implement SDRAM read
    0
}

#[unsafe(no_mangle)]
pub extern "C" fn sdram_write(_addr: i32, _data: u8) {
    // TODO: implement SDRAM write
}
