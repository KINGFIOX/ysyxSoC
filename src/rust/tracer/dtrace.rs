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

struct DeviceRegion {
    name: &'static str,
    base: u64,
    size: u64,
}

const DEVICE_MAP: &[DeviceRegion] = &[
    DeviceRegion { name: "UART",     base: 0x10000000, size: 0x1000 },
    DeviceRegion { name: "GPIO",     base: 0x10002000, size: 0x10 },
    DeviceRegion { name: "KBD",      base: 0x10011000, size: 0x8 },
    DeviceRegion { name: "SPI_CTRL", base: 0x10001000, size: 0x1000 },
    DeviceRegion { name: "MROM",     base: 0x20000000, size: 0x1000 },
    DeviceRegion { name: "VGA",      base: 0x21000000, size: 0x200000 },
    DeviceRegion { name: "FLASH",    base: 0x30000000, size: 0x10000000 },
    DeviceRegion { name: "SRAM",     base: 0x0f000000, size: 0x2000 },
    DeviceRegion { name: "PSRAM",    base: 0x80000000, size: 0x400000 },
    DeviceRegion { name: "SDRAM",    base: 0xa0000000, size: 0x2000000 },
];

fn resolve_addr(addr: u64) -> String {
    for dev in DEVICE_MAP {
        if addr.wrapping_sub(dev.base) < dev.size {
            let offset = addr - dev.base;
            return format!("{}+{offset:#x}", dev.name);
        }
    }
    format!("{addr:#018x}")
}

pub struct DTraceEntry {
    pub pc: u64,
    pub dir: MemDir,
    pub addr: u64,
    pub data: u64,
    pub width: u8,
    pub disasm: String,
}

impl DTraceEntry {
    pub fn new(pc: u64, dir: MemDir, addr: u64, data: u64, width: u8, disasm: &str) -> Self {
        Self { pc, dir, addr, data, width, disasm: disasm.to_string() }
    }
}

impl fmt::Display for DTraceEntry {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(
            f,
            "{:#018x}: {} {:14} data={:#018x} width={} ({})",
            self.pc, self.dir, resolve_addr(self.addr), self.data, self.width, self.disasm
        )
    }
}
