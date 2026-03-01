pub const CSR_MSTATUS: u16 = 0x0300;
pub const CSR_MTVEC: u16 = 0x0305;
pub const CSR_MEPC: u16 = 0x0341;
pub const CSR_MCAUSE: u16 = 0x0342;
pub const CSR_MTVAL: u16 = 0x0343;
pub const CSR_MVENDORID: u16 = 0x0F11;
pub const CSR_MARCHID: u16 = 0x0F12;

#[allow(dead_code)]
pub trait AbstractCpu {
    fn gpr(&self, index: usize) -> u32;
    fn set_gpr(&mut self, index: usize, value: u32);

    fn mstatus(&self) -> u32;
    fn set_mstatus(&mut self, value: u32);
    fn mtvec(&self) -> u32;
    fn set_mtvec(&mut self, value: u32);
    fn mepc(&self) -> u32;
    fn set_mepc(&mut self, value: u32);
    fn mcause(&self) -> u32;
    fn set_mcause(&mut self, value: u32);
    fn mtval(&self) -> u32;
    fn set_mtval(&mut self, value: u32);
    fn mvendorid(&self) -> u32;
    fn set_mvendorid(&mut self, value: u32);
    fn marchid(&self) -> u32;
    fn set_marchid(&mut self, value: u32);

    fn mem_load_u8(&self, addr: u32) -> miette::Result<u8>;
    fn mem_load_u16(&self, addr: u32) -> miette::Result<u16>;
    fn mem_load_u32(&self, addr: u32) -> miette::Result<u32>;
    fn mem_store_u8(&mut self, addr: u32, value: u8) -> miette::Result<()>;
    fn mem_store_u16(&mut self, addr: u32, value: u16) -> miette::Result<()>;
    fn mem_store_u32(&mut self, addr: u32, value: u32) -> miette::Result<()>;

    fn reset(&mut self);
    fn step(&mut self);
}
