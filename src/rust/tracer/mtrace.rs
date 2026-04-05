use std::fmt;

use crate::tracer::dtrace::{DTraceEntry, MemDir};

pub struct MTraceEntry(DTraceEntry);

impl MTraceEntry {
    pub fn new(pc: u64, dir: MemDir, addr: u64, data: u64, width: u8, disasm: &str) -> Self {
        Self(DTraceEntry::new(pc, dir, addr, data, width, disasm))
    }
}

impl fmt::Display for MTraceEntry {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(
            f,
            "{:#018x}: {:14} addr={:#018x} data={:#018x} width={} ({})",
            self.0.pc, self.0.dir, self.0.addr, self.0.data, self.0.width, self.0.disasm
        )
    }
}
