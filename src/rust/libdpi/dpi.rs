/// DPI-C callback functions called by Verilator during simulation.
///
/// These must match the `import "DPI-C"` declarations in the Verilog/SystemVerilog
/// sources exactly. Verilator resolves them at link time.
///
/// All addresses passed from RTL are device-relative offsets (base already stripped).
use crate::libdpi::globals::{DPI_FLASH, DPI_ICACHE, DPI_MROM, DPI_PSRAM, DPI_SDRAM};

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
pub extern "C" fn icache_refill(addr: i32, data: i32) {
    DPI_ICACHE.with_mut(|icache| {
        icache.refill(addr, data);
    })
}

#[unsafe(no_mangle)]
pub extern "C" fn icache_lookup(addr: i32, hit: *mut i32, data: *mut i32) {
    DPI_ICACHE.with_mut(|icache| {
        let d = icache.lookup(addr as u32);
        if let Some(d) = d {
            unsafe {
                *data = d as i32;
                *hit = 1;
            }
        } else {
            unsafe {
                *data = 0;
                *hit = 0;
            }
        }
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
pub extern "C" fn sdram_read_dpic(addr: i32) -> u16 {
    DPI_SDRAM.with(|sdram| {
        let offset = addr as u32 as usize;
        let lo = sdram[offset] as u16;
        let hi = sdram[offset + 1] as u16;
        lo | (hi << 8)
    })
}

#[allow(unused)]
use log::info;

#[unsafe(no_mangle)]
pub extern "C" fn sdram_write(addr: i32, data: u8) {
    // info!("addr={:#012x}, data={:#012x}", addr, data);
    DPI_SDRAM.with_mut(|sdram| {
        sdram[addr as u32 as usize] = data;
    });
}
