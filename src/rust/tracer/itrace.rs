use std::fmt;

pub struct ITraceEntry {
    pub pc: u64,
    pub inst: u32,
    pub disasm: String,
}

impl ITraceEntry {
    pub fn new(pc: u64, inst: u32, disasm: &str) -> Self {
        Self { pc, inst, disasm: disasm.to_string() }
    }
}

impl fmt::Display for ITraceEntry {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(f, "{:#018x}: {:08x}  {}", self.pc, self.inst, self.disasm)
    }
}
