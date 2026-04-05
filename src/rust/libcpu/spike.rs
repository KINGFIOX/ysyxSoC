use std::ffi::CStr;

use crate::ffi::*;
use crate::libcpu::abstract_cpu::{
    AbstractCpu, CSR_MARCHID, CSR_MCAUSE, CSR_MEPC, CSR_MSTATUS, CSR_MTVAL, CSR_MTVEC,
    CSR_MVENDORID,
};

const ISA: &CStr = c"RV64IMAFDC";
const RESET_VECTOR: u64 = 0x80000000;

const MEM_BASES: &[u64] = &[
    0x20000000, // MROM
    0x0f000000, // SRAM
    0x21000000, // VGA
    0x10001000, // SPI_CTRL
    0x30000000, // XIP_FLASH
    0x80000000, // SDRAM (DRAM, QEMU virt compatible)
    0x10000000, // uart
];

const MEM_SIZES: &[u64] = &[
    0x1000,     // MROM
    0x2000,     // SRAM
    0x200000,   // VGA
    0x1000,     // SPI_CTRL
    0x10000000, // XIP_FLASH
    0x8000000,  // SDRAM (128MB)
    0x1000,     // uart
];

pub struct SpikeCpu {
    sim: *mut sim_t,
    proc: *mut processor_t,
    state: *mut state_t,
    mmu: *mut mmu_t,
}

impl SpikeCpu {
    pub fn new(flash_data: &[u8]) -> Self {
        assert!(MEM_BASES.len() == MEM_SIZES.len());
        let mem_count: autocxx::c_int = (MEM_BASES.len() as i32).into();
        let sim = unsafe {
            sim_new(
                ISA.as_ptr(),
                MEM_BASES.as_ptr(),
                MEM_SIZES.as_ptr(),
                mem_count,
            )
        };

        let proc = unsafe { sim_get_core(sim, 0.into()) };
        let state = unsafe { proc_get_state(proc) };
        let mmu = unsafe { proc_get_mmu(proc) };

        unsafe { state_set_pc(state, RESET_VECTOR) };

        let mut cpu = Self {
            sim,
            proc,
            state,
            mmu,
        };
        for (i, &byte) in flash_data.iter().enumerate() {
            let addr = RESET_VECTOR + i as u64;
            cpu.mem_store(addr, byte as u64, 1).unwrap();
        }
        cpu
    }
}

impl Drop for SpikeCpu {
    fn drop(&mut self) {
        unsafe { sim_delete(self.sim) };
    }
}

impl AbstractCpu for SpikeCpu {
    fn pc(&self) -> u64 {
        unsafe { state_get_pc(self.state) }
    }

    fn set_pc(&mut self, value: u64) -> miette::Result<()> {
        if value % 4 != 0 {
            return Err(miette::Error::msg("pc must be aligned to 4 bytes"));
        }
        unsafe { state_set_pc(self.state, value) };
        Ok(())
    }

    fn gpr(&self, index: usize) -> miette::Result<u64> {
        if index >= 32 {
            return Err(miette::Error::msg("invalid register index"));
        }
        let gpr = unsafe { state_get_gpr(self.state, (index as i32).into()) };
        Ok(gpr)
    }

    fn set_gpr(&mut self, index: usize, value: u64) -> miette::Result<()> {
        if index >= 32 {
            return Err(miette::Error::msg("invalid register index"));
        }
        unsafe { state_set_gpr(self.state, (index as i32).into(), value) };
        Ok(())
    }

    fn mstatus(&self) -> u64 {
        unsafe { proc_get_csr(self.proc, (CSR_MSTATUS as i32).into()) }
    }

    fn set_mstatus(&mut self, value: u64) -> miette::Result<()> {
        unsafe { proc_put_csr(self.proc, (CSR_MSTATUS as i32).into(), value) };
        Ok(())
    }

    fn mtvec(&self) -> u64 {
        unsafe { proc_get_csr(self.proc, (CSR_MTVEC as i32).into()) }
    }

    fn set_mtvec(&mut self, value: u64) -> miette::Result<()> {
        unsafe { proc_put_csr(self.proc, (CSR_MTVEC as i32).into(), value) };
        Ok(())
    }

    fn mepc(&self) -> u64 {
        unsafe { proc_get_csr(self.proc, (CSR_MEPC as i32).into()) }
    }

    fn set_mepc(&mut self, value: u64) -> miette::Result<()> {
        unsafe { proc_put_csr(self.proc, (CSR_MEPC as i32).into(), value) };
        Ok(())
    }

    fn mcause(&self) -> u64 {
        unsafe { proc_get_csr(self.proc, (CSR_MCAUSE as i32).into()) }
    }

    fn set_mcause(&mut self, value: u64) -> miette::Result<()> {
        unsafe { proc_put_csr(self.proc, (CSR_MCAUSE as i32).into(), value) };
        Ok(())
    }

    fn mtval(&self) -> u64 {
        unsafe { proc_get_csr(self.proc, (CSR_MTVAL as i32).into()) }
    }

    fn set_mtval(&mut self, value: u64) -> miette::Result<()> {
        unsafe { proc_put_csr(self.proc, (CSR_MTVAL as i32).into(), value) };
        Ok(())
    }

    fn mvendorid(&self) -> u64 {
        unsafe { proc_get_csr(self.proc, (CSR_MVENDORID as i32).into()) }
    }

    fn set_mvendorid(&mut self, value: u64) -> miette::Result<()> {
        unsafe { proc_put_csr(self.proc, (CSR_MVENDORID as i32).into(), value) };
        Ok(())
    }

    fn marchid(&self) -> u64 {
        unsafe { proc_get_csr(self.proc, (CSR_MARCHID as i32).into()) }
    }

    fn set_marchid(&mut self, value: u64) -> miette::Result<()> {
        unsafe { proc_put_csr(self.proc, (CSR_MARCHID as i32).into(), value) };
        Ok(())
    }

    fn mem_load(&self, addr: u64, width: u8) -> miette::Result<u64> {
        match width {
            1 => {
                let mut v: u8 = 0;
                if unsafe { mmu_load_u8(self.mmu, addr, &mut v) } != 0.into() {
                    return Err(miette::miette!("spike trap: load u8 @ {addr:#018x}"));
                }
                Ok(v as u64)
            }
            2 => {
                let mut v: u16 = 0;
                if unsafe { mmu_load_u16(self.mmu, addr, &mut v) } != 0.into() {
                    return Err(miette::miette!("spike trap: load u16 @ {addr:#018x}"));
                }
                Ok(v as u64)
            }
            4 => {
                let mut v: u32 = 0;
                if unsafe { mmu_load_u32(self.mmu, addr, &mut v) } != 0.into() {
                    return Err(miette::miette!("spike trap: load u32 @ {addr:#018x}"));
                }
                Ok(v as u64)
            }
            8 => {
                let mut v: u64 = 0;
                if unsafe { mmu_load_u64(self.mmu, addr, &mut v) } != 0.into() {
                    return Err(miette::miette!("spike trap: load u64 @ {addr:#018x}"));
                }
                Ok(v)
            }
            _ => Err(miette::miette!("invalid load width: {width}")),
        }
    }

    fn mem_store(&mut self, addr: u64, value: u64, width: u8) -> miette::Result<()> {
        let rc = match width {
            1 => unsafe { mmu_store_u8(self.mmu, addr, value as u8) },
            2 => unsafe { mmu_store_u16(self.mmu, addr, value as u16) },
            4 => unsafe { mmu_store_u32(self.mmu, addr, value as u32) },
            8 => unsafe { mmu_store_u64(self.mmu, addr, value) },
            _ => return Err(miette::miette!("invalid store width: {width}")),
        };
        if rc != 0.into() {
            return Err(miette::miette!("spike trap: store w{width} @ {addr:#018x}"));
        }
        Ok(())
    }

    fn reset(&mut self) {
        unsafe { proc_reset(self.proc) };
    }

    fn step(&mut self) -> miette::Result<()> {
        let rc: i32 = unsafe { proc_step(self.proc, 1) }.into();
        if rc != 0 {
            return Err(miette::miette!("spike trap during step (pc={:#018x})", self.pc()));
        }
        Ok(())
    }
}
