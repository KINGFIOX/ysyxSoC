use std::fmt;

pub enum MemDir {
    Read,
    Write,
}

impl fmt::Display for MemDir {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            MemDir::Read => write!(f, "R"),
            MemDir::Write => write!(f, "W"),
        }
    }
}

pub struct DTraceEntry {
    pub pc: u32,
    pub dir: MemDir,
    pub addr: u32,
    pub data: u32,
    pub width: u8,
    pub disasm: String,
}

impl fmt::Display for DTraceEntry {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(
            f,
            "{:#010x}: {} addr={:#010x} data={:#010x} width={} ({})",
            self.pc, self.dir, self.addr, self.data, self.width, self.disasm
        )
    }
}
