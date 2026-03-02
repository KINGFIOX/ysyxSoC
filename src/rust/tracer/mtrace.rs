use std::fmt;

pub struct ITraceEntry {
    pub pc: u32,
    pub inst: u32,
    pub disasm: String,
}

impl fmt::Display for ITraceEntry {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(f, "{:#010x}: {:08x}  {}", self.pc, self.inst, self.disasm)
    }
}
