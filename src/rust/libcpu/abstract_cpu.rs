pub const CSR_MSTATUS: u16 = 0x0300;
pub const CSR_MTVEC: u16 = 0x0305;
pub const CSR_MEPC: u16 = 0x0341;
pub const CSR_MCAUSE: u16 = 0x0342;
pub const CSR_MTVAL: u16 = 0x0343;
pub const CSR_MVENDORID: u16 = 0x0F11;
pub const CSR_MARCHID: u16 = 0x0F12;

#[allow(dead_code)]
pub trait AbstractCpu {
    fn pc(&self) -> u32;

    fn gpr(&self, index: usize) -> miette::Result<u32>;
    fn set_gpr(&mut self, index: usize, value: u32) -> miette::Result<()>;

    fn value(&self, name: &str) -> miette::Result<u32> {
        match name {
            "pc" => Ok(self.pc()),
            "mstatus" => Ok(self.mstatus()),
            "mtvec" => Ok(self.mtvec()),
            "mepc" => Ok(self.mepc()),
            "mcause" => Ok(self.mcause()),
            "mtval" => Ok(self.mtval()),
            "mvendorid" => Ok(self.mvendorid()),
            "marchid" => Ok(self.marchid()),
            "x0" | "zero" => Ok(0),
            "x1" | "ra" => self.gpr(1),
            "x2" | "sp" => self.gpr(2),
            "x3" | "gp" => self.gpr(3),
            "x4" | "tp" => self.gpr(4),
            "x5" | "t0" => self.gpr(5),
            "x6" | "t1" => self.gpr(6),
            "x7" | "t2" => self.gpr(7),
            "x8" | "s0" | "fp" => self.gpr(8),
            "x9" | "s1" => self.gpr(9),
            "x10" | "a0" => self.gpr(10),
            "x11" | "a1" => self.gpr(11),
            "x12" | "a2" => self.gpr(12),
            "x13" | "a3" => self.gpr(13),
            "x14" | "a4" => self.gpr(14),
            "x15" | "a5" => self.gpr(15),
            "x16" | "a6" => self.gpr(16),
            "x17" | "a7" => self.gpr(17),
            "x18" | "s2" => self.gpr(18),
            "x19" | "s3" => self.gpr(19),
            "x20" | "s4" => self.gpr(20),
            "x21" | "s5" => self.gpr(21),
            "x22" | "s6" => self.gpr(22),
            "x23" | "s7" => self.gpr(23),
            "x24" | "s8" => self.gpr(24),
            "x25" | "s9" => self.gpr(25),
            "x26" | "s10" => self.gpr(26),
            "x27" | "s11" => self.gpr(27),
            "x28" | "t3" => self.gpr(28),
            "x29" | "t4" => self.gpr(29),
            "x30" | "t5" => self.gpr(30),
            "x31" | "t6" => self.gpr(31),
            _ => Err(miette::Error::msg(format!(
                "invalid register name: {}",
                name
            ))),
        }
    }

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
    fn step(&mut self) -> miette::Result<()>;
}
