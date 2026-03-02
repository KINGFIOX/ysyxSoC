use std::cell::UnsafeCell;

pub const PSRAM_BASE: u32 = 0x80000000;
pub const PSRAM_SIZE: u32 = 0x400000;
pub const MROM_BASE: u32 = 0x20000000;
pub const MROM_SIZE: u32 = 0x1000;
pub const SRAM_BASE: u32 = 0x0f000000;
pub const SRAM_SIZE: u32 = 0x2000;
pub const SDRAM_BASE: u32 = 0xa0000000;
pub const SDRAM_SIZE: u32 = 0x2000000;
pub const FLASH_BASE: u32 = 0x30000000;
pub const FLASH_SIZE: u32 = 0x10000000;

/// Single-threaded interior-mutable cell that can live in a `static`.
/// SAFETY: only safe when all access is from a single thread, which holds
/// for Verilator simulation (DPI-C callbacks + main thread are the same thread).
struct SyncCell<T>(UnsafeCell<T>);
unsafe impl<T> Sync for SyncCell<T> {}

impl<T> SyncCell<T> {
    const fn new(val: T) -> Self {
        Self(UnsafeCell::new(val))
    }
    fn get(&self) -> *mut T {
        self.0.get()
    }
}

static FLASH: SyncCell<Vec<u8>> = SyncCell::new(Vec::new());
static MROM: SyncCell<Vec<u8>> = SyncCell::new(Vec::new());
static PSRAM: SyncCell<Vec<u8>> = SyncCell::new(Vec::new());
static SRAM: SyncCell<Vec<u8>> = SyncCell::new(Vec::new());
static SDRAM: SyncCell<Vec<u8>> = SyncCell::new(Vec::new());

pub fn init(flash_data: &[u8]) {
    fn alloc(cell: &SyncCell<Vec<u8>>, size: u32, content: &[u8]) {
        let v = unsafe { &mut *cell.get() };
        *v = vec![0u8; size as usize];
        v[..content.len()].copy_from_slice(content);
    }
    alloc(&FLASH, FLASH_SIZE, flash_data);
    alloc(&MROM, MROM_SIZE, &[]);
    alloc(&PSRAM, PSRAM_SIZE, &[]);
    alloc(&SRAM, SRAM_SIZE, &[]);
    alloc(&SDRAM, SDRAM_SIZE, &[]);
}

fn read_byte(cell: &SyncCell<Vec<u8>>, offset: u32) -> u8 {
    let v = unsafe { &*cell.get() };
    v.get(offset as usize).copied().unwrap_or(0)
}

fn write_byte(cell: &SyncCell<Vec<u8>>, offset: u32, val: u8) {
    let v = unsafe { &mut *cell.get() };
    if let Some(b) = v.get_mut(offset as usize) {
        *b = val;
    }
}

pub fn flash_read(offset: u32) -> u8 { read_byte(&FLASH, offset) }
pub fn mrom_read(offset: u32) -> u8 { read_byte(&MROM, offset) }
pub fn psram_read(offset: u32) -> u8 { read_byte(&PSRAM, offset) }
pub fn psram_write(offset: u32, val: u8) { write_byte(&PSRAM, offset, val) }
pub fn sram_read(offset: u32) -> u8 { read_byte(&SRAM, offset) }
pub fn sram_write(offset: u32, val: u8) { write_byte(&SRAM, offset, val) }
pub fn sdram_read(offset: u32) -> u8 { read_byte(&SDRAM, offset) }
pub fn sdram_write(offset: u32, val: u8) { write_byte(&SDRAM, offset, val) }

pub fn load_u8(addr: u32) -> miette::Result<u8> {
    match addr {
        a if a.wrapping_sub(FLASH_BASE) < FLASH_SIZE => Ok(flash_read(a - FLASH_BASE)),
        a if a.wrapping_sub(MROM_BASE) < MROM_SIZE => Ok(mrom_read(a - MROM_BASE)),
        a if a.wrapping_sub(PSRAM_BASE) < PSRAM_SIZE => Ok(psram_read(a - PSRAM_BASE)),
        a if a.wrapping_sub(SRAM_BASE) < SRAM_SIZE => Ok(sram_read(a - SRAM_BASE)),
        a if a.wrapping_sub(SDRAM_BASE) < SDRAM_SIZE => Ok(sdram_read(a - SDRAM_BASE)),
        _ => Err(miette::Error::msg(format!("address {addr:#x} is out of range"))),
    }
}

pub fn store_u8(addr: u32, val: u8) -> miette::Result<()> {
    match addr {
        a if a.wrapping_sub(PSRAM_BASE) < PSRAM_SIZE => { psram_write(a - PSRAM_BASE, val); Ok(()) }
        a if a.wrapping_sub(SRAM_BASE) < SRAM_SIZE => { sram_write(a - SRAM_BASE, val); Ok(()) }
        a if a.wrapping_sub(SDRAM_BASE) < SDRAM_SIZE => { sdram_write(a - SDRAM_BASE, val); Ok(()) }
        a if a.wrapping_sub(FLASH_BASE) < FLASH_SIZE => Err(miette::Error::msg("write to read-only flash")),
        a if a.wrapping_sub(MROM_BASE) < MROM_SIZE => Err(miette::Error::msg("write to read-only mrom")),
        _ => Err(miette::Error::msg(format!("address {addr:#x} is out of range"))),
    }
}
