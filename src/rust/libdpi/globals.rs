use std::sync::{Arc, Mutex};

use crate::libdpi::target::DpiTarget;

pub const MROM_BASE: u64 = 0x20000000;
pub const MROM_SIZE: u64 = 0x1000;
pub const SRAM_BASE: u64 = 0x0f000000;
pub const SRAM_SIZE: u64 = 0x2000;
pub const FLASH_BASE: u64 = 0x30000000;
pub const FLASH_SIZE: u64 = 0x10000000;
pub const SDRAM_BASE: u64 = 0x80000000;
pub const SDRAM_SIZE: u64 = 0x10000000;

pub static DPI_FLASH: DpiTarget<Vec<u8>> = DpiTarget::new();
pub static DPI_MROM: DpiTarget<Vec<u8>> = DpiTarget::new();
pub static DPI_SRAM: DpiTarget<Vec<u8>> = DpiTarget::new();
pub static DPI_SDRAM: DpiTarget<Vec<u8>> = DpiTarget::new();

pub struct Memory {
    flash: Arc<Mutex<Vec<u8>>>,
    mrom: Arc<Mutex<Vec<u8>>>,
    sram: Arc<Mutex<Vec<u8>>>,
    sdram: Arc<Mutex<Vec<u8>>>,
}

impl Memory {
    pub fn new(flash_data: &[u8]) -> Self {
        let mut flash = vec![0u8; FLASH_SIZE as usize];
        flash[..flash_data.len()].copy_from_slice(flash_data);
        Self {
            flash: Arc::new(Mutex::new(flash)),
            mrom: Arc::new(Mutex::new(vec![0u8; MROM_SIZE as usize])),
            sram: Arc::new(Mutex::new(vec![0u8; SRAM_SIZE as usize])),
            sdram: Arc::new(Mutex::new(vec![0u8; SDRAM_SIZE as usize])),
        }
    }

    pub fn init_dpi(&self) {
        DPI_FLASH.init(Arc::clone(&self.flash));
        DPI_MROM.init(Arc::clone(&self.mrom));
        DPI_SRAM.init(Arc::clone(&self.sram));
        DPI_SDRAM.init(Arc::clone(&self.sdram));
    }

    pub fn load_u8(&self, addr: u64) -> miette::Result<u8> {
        match addr {
            a if a.wrapping_sub(FLASH_BASE) < FLASH_SIZE => {
                Ok(self.flash.lock().unwrap()[(a - FLASH_BASE) as usize])
            }
            a if a.wrapping_sub(MROM_BASE) < MROM_SIZE => {
                Ok(self.mrom.lock().unwrap()[(a - MROM_BASE) as usize])
            }
            a if a.wrapping_sub(SRAM_BASE) < SRAM_SIZE => {
                Ok(self.sram.lock().unwrap()[(a - SRAM_BASE) as usize])
            }
            a if a.wrapping_sub(SDRAM_BASE) < SDRAM_SIZE => {
                Ok(self.sdram.lock().unwrap()[(a - SDRAM_BASE) as usize])
            }
            _ => Err(miette::Error::msg(format!(
                "address {addr:#x} is out of range"
            ))),
        }
    }

    pub fn store_u8(&self, addr: u64, val: u8) -> miette::Result<()> {
        match addr {
            a if a.wrapping_sub(SRAM_BASE) < SRAM_SIZE => {
                self.sram.lock().unwrap()[(a - SRAM_BASE) as usize] = val;
                Ok(())
            }
            a if a.wrapping_sub(SDRAM_BASE) < SDRAM_SIZE => {
                self.sdram.lock().unwrap()[(a - SDRAM_BASE) as usize] = val;
                Ok(())
            }
            a if a.wrapping_sub(FLASH_BASE) < FLASH_SIZE => {
                Err(miette::Error::msg("write to read-only flash"))
            }
            a if a.wrapping_sub(MROM_BASE) < MROM_SIZE => {
                Err(miette::Error::msg("write to read-only mrom"))
            }
            _ => Err(miette::Error::msg(format!(
                "address {addr:#x} is out of range"
            ))),
        }
    }
}
