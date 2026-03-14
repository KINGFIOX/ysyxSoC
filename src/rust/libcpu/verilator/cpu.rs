use autocxx::c_void;

use std::ffi::CStr;

use crate::ffi::*;
use crate::libcpu::abstract_cpu::AbstractCpu;
use crate::libdpi::globals::Memory;

const TOP_NAME: &CStr = c"TOP";
const VCD_PATH: &CStr = c"build/npc_core.vcd";
const RESET_CYCLES: usize = 15;
const MAX_STEP_CYCLES: usize = 1_000_00;

pub struct VerilatorCpu {
    ctx: *mut VerilatedContext,
    top: *mut VNPCSoC,
    tfp: Option<*mut VerilatedVcdC>,
    sim_time: u64,
    nvboard: bool,
    mem: Memory,
}

impl VerilatorCpu {
    pub fn new(flash_data: &[u8], wave: bool, nvboard: bool) -> Self {
        let mem = Memory::new(flash_data);
        mem.init_dpi();

        let ctx = vl_context_new();
        let top = unsafe { vnpcsoc_new(ctx, TOP_NAME.as_ptr()) };

        vl_trace_ever_on(true);
        let tfp = if wave {
            let tfp = vl_vcd_new();
            unsafe { vnpcsoc_trace(top, tfp, autocxx::c_int(99)) };
            unsafe { vl_vcd_open(tfp, VCD_PATH.as_ptr()) };
            Some(tfp)
        } else {
            None
        };

        if nvboard {
            unsafe { nvboard_bridge_init(top as *mut c_void, autocxx::c_int(1)) };
        }

        let mut cpu = Self { ctx, top, tfp, sim_time: 0, nvboard, mem };
        cpu.reset();
        cpu
    }

    fn tick(&mut self) {
        unsafe {
            vnpcsoc_set_clock(self.top, 0);
            vnpcsoc_eval(self.top);
            if let Some(tfp) = self.tfp {
                vl_vcd_dump(tfp, self.sim_time);
            }
            self.sim_time += 1;

            vnpcsoc_set_clock(self.top, 1);
            vnpcsoc_eval(self.top);
            if let Some(tfp) = self.tfp {
                vl_vcd_dump(tfp, self.sim_time);
            }
            self.sim_time += 1;

            if self.nvboard {
                nvboard_bridge_update();
            }
        }
    }
}

impl VerilatorCpu {
    pub fn is_mmio(&self) -> bool {
        unsafe { vnpcsoc_get_debug_isMMIO(self.top) != 0 }
    }

    pub fn dnpc(&self) -> u32 {
        unsafe { vnpcsoc_get_debug_dnpc(self.top) as u32 }
    }

    pub fn inst(&self) -> u32 {
        unsafe { vnpcsoc_get_debug_inst(self.top) as u32 }
    }
}

impl Drop for VerilatorCpu {
    fn drop(&mut self) {
        if self.nvboard {
            nvboard_bridge_quit();
        }
        unsafe {
            if let Some(tfp) = self.tfp {
                vl_vcd_flush(tfp);
                vl_vcd_close(tfp);
                vl_vcd_delete(tfp);
            }
            vnpcsoc_delete(self.top);
            vl_context_delete(self.ctx);
        }
    }
}

impl AbstractCpu for VerilatorCpu {
    fn pc(&self) -> u32 {
        unsafe { vnpcsoc_get_debug_pc(self.top) }
    }

    fn set_pc(&mut self, _value: u32) -> miette::Result<()> {
        Err(miette::Error::msg("dut should not call set_pc"))
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

    fn set_mstatus(&mut self, _value: u32) -> miette::Result<()> {
        Err(miette::Error::msg("dut should not call set_mstatus"))
    }

    fn mtvec(&self) -> u32 {
        unsafe { vnpcsoc_get_debug_csr_mtvec(self.top) }
    }

    fn set_mtvec(&mut self, _value: u32) -> miette::Result<()> {
        Err(miette::Error::msg("dut should not call set_mtvec"))
    }

    fn mepc(&self) -> u32 {
        unsafe { vnpcsoc_get_debug_csr_mepc(self.top) }
    }

    fn set_mepc(&mut self, _value: u32) -> miette::Result<()> {
        Err(miette::Error::msg("dut should not call set_mepc"))
    }

    fn mcause(&self) -> u32 {
        unsafe { vnpcsoc_get_debug_csr_mcause(self.top) }
    }

    fn set_mcause(&mut self, _value: u32) -> miette::Result<()> {
        Err(miette::Error::msg("dut should not call set_mcause"))
    }

    fn mtval(&self) -> u32 {
        unsafe { vnpcsoc_get_debug_csr_mtval(self.top) }
    }

    fn set_mtval(&mut self, _value: u32) -> miette::Result<()> {
        Err(miette::Error::msg("dut should not call set_mtval"))
    }

    fn mvendorid(&self) -> u32 {
        unsafe { vnpcsoc_get_debug_csr_mvendorid(self.top) }
    }

    fn set_mvendorid(&mut self, _value: u32) -> miette::Result<()> {
        Err(miette::Error::msg("dut should not call set_mvendorid"))
    }

    fn marchid(&self) -> u32 {
        unsafe { vnpcsoc_get_debug_csr_marchid(self.top) }
    }

    fn set_marchid(&mut self, _value: u32) -> miette::Result<()> {
        Err(miette::Error::msg("dut should not call set_marchid"))
    }

    fn mem_load_u8(&self, addr: u32) -> miette::Result<u8> {
        self.mem.load_u8(addr)
    }

    fn mem_load_u16(&self, addr: u32) -> miette::Result<u16> {
        let b0 = self.mem.load_u8(addr)? as u16;
        let b1 = self.mem.load_u8(addr + 1)? as u16;
        Ok(b0 | (b1 << 8))
    }

    fn mem_load_u32(&self, addr: u32) -> miette::Result<u32> {
        let b0 = self.mem.load_u8(addr)? as u32;
        let b1 = self.mem.load_u8(addr + 1)? as u32;
        let b2 = self.mem.load_u8(addr + 2)? as u32;
        let b3 = self.mem.load_u8(addr + 3)? as u32;
        Ok(b0 | (b1 << 8) | (b2 << 16) | (b3 << 24))
    }

    fn mem_store_u8(&mut self, addr: u32, value: u8) -> miette::Result<()> {
        self.mem.store_u8(addr, value)
    }

    fn mem_store_u16(&mut self, addr: u32, value: u16) -> miette::Result<()> {
        self.mem.store_u8(addr, value as u8)?;
        self.mem.store_u8(addr + 1, (value >> 8) as u8)?;
        Ok(())
    }

    fn mem_store_u32(&mut self, addr: u32, value: u32) -> miette::Result<()> {
        self.mem.store_u8(addr, value as u8)?;
        self.mem.store_u8(addr + 1, (value >> 8) as u8)?;
        self.mem.store_u8(addr + 2, (value >> 16) as u8)?;
        self.mem.store_u8(addr + 3, (value >> 24) as u8)?;
        Ok(())
    }

    fn reset(&mut self) {
        unsafe { vnpcsoc_set_reset(self.top, 1) };
        for _ in 0..RESET_CYCLES {
            self.tick();
        }
        unsafe { vnpcsoc_set_reset(self.top, 0) };
    }

    fn step(&mut self) -> miette::Result<()> {

        for i in 0..MAX_STEP_CYCLES {
            self.tick();
            if unsafe { vnpcsoc_get_debug_valid(self.top) } != 0 {
                break;
            }
            if i == MAX_STEP_CYCLES - 1 {
                return Err(miette::Error::msg(format!("step exceeded {} cycles without debug_valid", MAX_STEP_CYCLES)));
            }
        }
        unsafe {
            vnpcsoc_eval(self.top);
        }
        Ok(())
    }
}
