use super::*;
use std::fmt;

pub struct MTraceEntry(DTraceEntry);

impl MTraceEntry {
    pub fn new(pc: u32, dir: MemDir, addr: u32, data: u32, width: u8, disasm: &str) -> Self {
        Self(DTraceEntry::new(pc, dir, addr, data, width, disasm))
    }
}

impl fmt::Display for MTraceEntry {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(
            f,
            "{:#010x}: {:14} data={:#010x} width={} ({})",
            self.0.pc, self.0.dir, self.0.data, self.0.width, self.0.disasm
        )
    }
}
