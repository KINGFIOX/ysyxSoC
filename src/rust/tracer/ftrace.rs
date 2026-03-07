use object::{Object, ObjectSymbol, SymbolKind};
use std::{collections::HashMap, fmt};

use crate::tracer::ringbuf::RingBuf;

const FTRACE_CAPACITY: usize = 32;

pub struct FuncTracer {
    pub ring_buf: RingBuf<FTraceEntry>,
    pub symtab: HashMap<u32, String>,
    depth: u32,
}

impl FuncTracer {
    pub fn new(elf_path: &std::path::Path) -> Self {
        let data = std::fs::read(elf_path).expect("failed to read ELF");
        let obj = object::File::parse(&*data).expect("failed to parse ELF");
        let mut symtab = HashMap::new();
        for sym in obj.symbols() {
            if sym.kind() == SymbolKind::Text && sym.size() > 0 {
                if let Ok(name) = sym.name() {
                    symtab.insert(sym.address() as u32, name.to_string());
                }
            }
        }
        Self {
            ring_buf: RingBuf::new(FTRACE_CAPACITY),
            symtab,
            depth: 0,
        }
    }

    pub fn push_call(&mut self, pc: u32, dnpc: u32, disasm: &str) {
        let func_name = self
            .symtab
            .get(&dnpc)
            .cloned()
            .unwrap_or_else(|| format!("{dnpc:#010x}"));
        let entry = FTraceEntry::new(pc, dnpc, self.depth, FuncType::Call(func_name), disasm);
        self.ring_buf.push(entry);
        self.depth += 1;
    }

    pub fn push_ret(&mut self, pc: u32, dnpc: u32, disasm: &str) {
        self.depth = self.depth.saturating_sub(1);
        let entry = FTraceEntry::new(pc, dnpc, self.depth, FuncType::Ret, disasm);
        self.ring_buf.push(entry);
    }
}

pub enum FuncType {
    Call(String),
    Ret,
}

pub struct FTraceEntry {
    pub pc: u32,
    pub dnpc: u32,
    pub depth: u32,
    pub func_type: FuncType,
    pub disasm: String,
}

impl FTraceEntry {
    pub fn new(pc: u32, dnpc: u32, depth: u32, func_type: FuncType, disasm: &str) -> Self {
        Self {
            pc,
            dnpc,
            depth,
            func_type,
            disasm: disasm.to_string(),
        }
    }
}

impl fmt::Display for FTraceEntry {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        let indent = "  ".repeat(self.depth as usize);
        match &self.func_type {
            FuncType::Call(name) => {
                write!(
                    f,
                    "{:#010x}: {indent}call [{name}@{:#010x}] ({})",
                    self.pc, self.dnpc, self.disasm
                )
            }
            FuncType::Ret => {
                write!(f, "{:#010x}: {indent}ret ({})", self.pc, self.disasm)
            }
        }
    }
}
