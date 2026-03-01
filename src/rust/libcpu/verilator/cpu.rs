use super::*;
use crate::ffi::*;

use std::ffi::CStr;

const TOP_NAME: &CStr = c"TOP";
const VCD_PATH: &CStr = c"build/npc_core.vcd";
const RESET_CYCLES: usize = 15;
const MAX_STEP_CYCLES: usize = 1_000_000;

const PSRAM_BASE: u32 = 0x80000000;
const PSRAM_SIZE: u32 = 0x400000;
const MROM_BASE: u32 = 0x20000000;
const MROM_SIZE: u32 = 0x1000;
const SRAM_BASE: u32 = 0x0f000000;
const SRAM_SIZE: u32 = 0x2000;
const SDRAM_BASE: u32 = 0xa0000000;
const SDRAM_SIZE: u32 = 0x2000000;
const FLASH_BASE: u32 = 0x30000000;
const FLASH_SIZE: u32 = 0x10000000;

pub struct VerilatorCpu {
    ctx: *mut VerilatedContext,
    top: *mut VNPCSoC,
    tfp: *mut VerilatedVcdC,
    sim_time: u64,
    // devices: Vec<(u32, Box<dyn AbstractDevice>)>,
    flash: ReadOnlyDevice,
    mrom: ReadOnlyDevice,
    psram: ReadWriteDevice,
    sram: ReadWriteDevice,
    sdram: ReadWriteDevice,
}

impl VerilatorCpu {
    pub fn new() -> Self {
        let ctx = vl_context_new();
        let top = unsafe { vnpcsoc_new(ctx, TOP_NAME.as_ptr()) };

        vl_trace_ever_on(true);
        let tfp = vl_vcd_new();
        unsafe { vnpcsoc_trace(top, tfp, autocxx::c_int(99)) };
        unsafe { vl_vcd_open(tfp, VCD_PATH.as_ptr()) };

        let mut cpu = Self {
            ctx,
            top,
            tfp,
            sim_time: 0,
            flash: ReadOnlyDevice::new(&[], FLASH_SIZE),
            mrom: ReadOnlyDevice::new(&[], MROM_SIZE),
            psram: ReadWriteDevice::new(PSRAM_SIZE),
            sram: ReadWriteDevice::new(SRAM_SIZE),
            sdram: ReadWriteDevice::new(SDRAM_SIZE),
        };
        cpu.reset();
        cpu
    }

    fn tick(&mut self) {
        unsafe {
            vnpcsoc_set_clock(self.top, 0);
            vnpcsoc_eval(self.top);
            vl_vcd_dump(self.tfp, self.sim_time);
            self.sim_time += 1;

            vnpcsoc_set_clock(self.top, 1);
            vnpcsoc_eval(self.top);
            vl_vcd_dump(self.tfp, self.sim_time);
            self.sim_time += 1;
        }
    }
}

impl Drop for VerilatorCpu {
    fn drop(&mut self) {
        unsafe {
            vl_vcd_flush(self.tfp);
            vl_vcd_close(self.tfp);
            vl_vcd_delete(self.tfp);
            vnpcsoc_delete(self.top);
            vl_context_delete(self.ctx);
        }
    }
}

impl AbstractCpu for VerilatorCpu {
    fn pc(&self) -> u32 {
        unsafe { vnpcsoc_get_debug_pc(self.top) }
    }

    fn gpr(&self, index: usize) -> miette::Result<u32> {
        if index >= 32 {
            return Err(miette::Error::msg("invalid register index"));
        }
        Ok(unsafe { vnpcsoc_get_debug_gpr(self.top, (index as i32).into()) })
    }

    fn set_gpr(&mut self, _index: usize, _value: u32) -> miette::Result<()> {
        if _index >= 32 {
            return Err(miette::Error::msg("invalid register index"));
        }
        Err(miette::Error::msg("dut should not call set_gpr"))
    }

    fn mstatus(&self) -> u32 {
        unsafe { vnpcsoc_get_debug_csr_mstatus(self.top) }
    }

    fn set_mstatus(&mut self, _value: u32) {
        panic!("dut should not call set_mstatus");
    }

    fn mtvec(&self) -> u32 {
        unsafe { vnpcsoc_get_debug_csr_mtvec(self.top) }
    }

    fn set_mtvec(&mut self, _value: u32) {
        panic!("dut should not call set_mtvec");
    }

    fn mepc(&self) -> u32 {
        unsafe { vnpcsoc_get_debug_csr_mepc(self.top) }
    }

    fn set_mepc(&mut self, _value: u32) {
        panic!("dut should not call set_mepc");
    }

    fn mcause(&self) -> u32 {
        unsafe { vnpcsoc_get_debug_csr_mcause(self.top) }
    }

    fn set_mcause(&mut self, _value: u32) {
        panic!("dut should not call set_mcause");
    }

    fn mtval(&self) -> u32 {
        unsafe { vnpcsoc_get_debug_csr_mtval(self.top) }
    }

    fn set_mtval(&mut self, _value: u32) {
        panic!("dut should not call set_mtval");
    }

    fn mvendorid(&self) -> u32 {
        unsafe { vnpcsoc_get_debug_csr_mvendorid(self.top) }
    }

    fn set_mvendorid(&mut self, _value: u32) {
        panic!("dut should not call set_mvendorid");
    }

    fn marchid(&self) -> u32 {
        unsafe { vnpcsoc_get_debug_csr_marchid(self.top) }
    }

    fn set_marchid(&mut self, _value: u32) {
        panic!("dut should not call set_marchid");
    }

    fn mem_load_u8(&self, _addr: u32) -> miette::Result<u8> {
        let (device, offset): (&dyn AbstractDevice, u32) = match _addr {
            a if a.wrapping_sub(FLASH_BASE) < FLASH_SIZE => (&self.flash, a - FLASH_BASE),
            a if a.wrapping_sub(MROM_BASE) < MROM_SIZE => (&self.mrom, a - MROM_BASE),
            a if a.wrapping_sub(PSRAM_BASE) < PSRAM_SIZE => (&self.psram, a - PSRAM_BASE),
            a if a.wrapping_sub(SRAM_BASE) < SRAM_SIZE => (&self.sram, a - SRAM_BASE),
            a if a.wrapping_sub(SDRAM_BASE) < SDRAM_SIZE => (&self.sdram, a - SDRAM_BASE),
            _ => return Err(miette::Error::msg(format!("address {:#x} is out of range", _addr))),
        };
        device.read(offset)
    }

    fn mem_load_u16(&self, _addr: u32) -> miette::Result<u16> {
        let b0 = self.mem_load_u8(_addr)? as u16;
        let b1 = self.mem_load_u8(_addr + 1)? as u16;
        Ok(b0 | (b1 << 8))
    }

    fn mem_load_u32(&self, _addr: u32) -> miette::Result<u32> {
        let b0 = self.mem_load_u8(_addr)? as u32;
        let b1 = self.mem_load_u8(_addr + 1)? as u32;
        let b2 = self.mem_load_u8(_addr + 2)? as u32;
        let b3 = self.mem_load_u8(_addr + 3)? as u32;
        Ok(b0 | (b1 << 8) | (b2 << 16) | (b3 << 24))
    }

    fn mem_store_u8(&mut self, _addr: u32, _value: u8) -> miette::Result<()> {
        let (device, offset): (&mut dyn AbstractDevice, u32) = match _addr {
            a if a.wrapping_sub(FLASH_BASE) < FLASH_SIZE => (&mut self.flash, a - FLASH_BASE),
            a if a.wrapping_sub(MROM_BASE) < MROM_SIZE => (&mut self.mrom, a - MROM_BASE),
            a if a.wrapping_sub(PSRAM_BASE) < PSRAM_SIZE => (&mut self.psram, a - PSRAM_BASE),
            a if a.wrapping_sub(SRAM_BASE) < SRAM_SIZE => (&mut self.sram, a - SRAM_BASE),
            a if a.wrapping_sub(SDRAM_BASE) < SDRAM_SIZE => (&mut self.sdram, a - SDRAM_BASE),
            _ => return Err(miette::Error::msg(format!("address {:#x} is out of range", _addr))),
        };
        device.write(offset, _value)
    }

    fn mem_store_u16(&mut self, _addr: u32, _value: u16) -> miette::Result<()> {
        self.mem_store_u8(_addr, _value as u8)?;
        self.mem_store_u8(_addr + 1, (_value >> 8) as u8)?;
        Ok(())
    }

    fn mem_store_u32(&mut self, _addr: u32, _value: u32) -> miette::Result<()> {
        self.mem_store_u8(_addr, _value as u8)?;
        self.mem_store_u8(_addr + 1, (_value >> 8) as u8)?;
        self.mem_store_u8(_addr + 2, (_value >> 16) as u8)?;
        self.mem_store_u8(_addr + 3, (_value >> 24) as u8)?;
        Ok(())
    }

    fn reset(&mut self) {
        unsafe { vnpcsoc_set_reset(self.top, 1) };
        for _ in 0..RESET_CYCLES {
            self.tick();
        }
        unsafe { vnpcsoc_set_reset(self.top, 0) };
    }

    fn step(&mut self) {
        unsafe { vnpcsoc_set_step(self.top, 1) };

        for i in 0..MAX_STEP_CYCLES {
            self.tick();
            if unsafe { vnpcsoc_get_debug_valid(self.top) } != 0 {
                break;
            }
            if i == MAX_STEP_CYCLES - 1 {
                panic!(
                    "step exceeded {} cycles without debug_valid",
                    MAX_STEP_CYCLES
                );
            }
        }

        unsafe {
            vnpcsoc_set_step(self.top, 0);
            vnpcsoc_eval(self.top);
        }
    } 
}
