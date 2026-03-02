use capstone::prelude::*;
#[allow(unused_imports)]
use log::{error, info};

use crate::libcpu::{AbstractCpu, SpikeCpu, VerilatorCpu};
use crate::tracer::{DTraceEntry, FuncTracer, ITraceEntry, MTraceEntry, MemDir, RingBuf};

const TRACE_CAPACITY: usize = 16;

const LOAD_MNEMONICS: &[&str] = &["lb", "lh", "lw", "lbu", "lhu"];
const STORE_MNEMONICS: &[&str] = &["sb", "sh", "sw"];

pub enum StepResult {
    Continue,
    EBreak(u32),
    DifftestFail,
}

const GPR_NAMES: &[&str] = &[
    "$0", "ra", "sp", "gp", "tp", "t0", "t1", "t2", "s0", "s1", "a0", "a1", "a2", "a3", "a4", "a5",
    "a6", "a7", "s2", "s3", "s4", "s5", "s6", "s7", "s8", "s9", "s10", "s11", "t3", "t4", "t5",
    "t6",
];

fn rd(inst: u32) -> usize {
    ((inst >> 7) & 0x1f) as usize
}

fn rs1(inst: u32) -> usize {
    ((inst >> 15) & 0x1f) as usize
}

fn rs2(inst: u32) -> usize {
    ((inst >> 20) & 0x1f) as usize
}

fn imm_i(inst: u32) -> i32 {
    (inst as i32) >> 20
}

fn imm_s(inst: u32) -> i32 {
    let hi = (inst >> 25) & 0x7f;
    let lo = (inst >> 7) & 0x1f;
    let raw = (hi << 5) | lo;
    ((raw as i32) << 20) >> 20
}

fn mem_width(mnemonic: &str) -> u8 {
    match mnemonic {
        "lb" | "lbu" | "sb" => 1,
        "lh" | "lhu" | "sh" => 2,
        "lw" | "sw" => 4,
        _ => 0,
    }
}

pub struct ScoreBoard {
    golden: SpikeCpu,
    cs: Capstone,
    itrace: RingBuf<ITraceEntry>,
    dtrace: RingBuf<DTraceEntry>,
    mtrace: RingBuf<MTraceEntry>,
    ftrace: Box<FuncTracer>,
}

impl ScoreBoard {
    pub fn new(flash_data: &[u8], ftrace: Box<FuncTracer>) -> Self {
        let cs = Capstone::new()
            .riscv()
            .mode(arch::riscv::ArchMode::RiscV32)
            .build()
            .expect("failed to create capstone instance");
        Self {
            golden: SpikeCpu::new(flash_data),
            cs,
            itrace: RingBuf::new(TRACE_CAPACITY),
            dtrace: RingBuf::new(TRACE_CAPACITY),
            mtrace: RingBuf::new(TRACE_CAPACITY),
            ftrace
        }
    }

    fn disasm(&self, inst: u32, pc: u32) -> (String, String) {
        let bytes = inst.to_le_bytes();
        match self.cs.disasm_all(&bytes, pc as u64) {
            Ok(insns) => match insns.iter().next() {
                Some(i) => {
                    let mn = i.mnemonic().unwrap_or("???").to_string();
                    let full = format!("{} {}", mn, i.op_str().unwrap_or(""));
                    (mn, full)
                }
                None => (String::new(), format!("unknown({inst:#010x})")),
            },
            Err(_) => (String::new(), format!("unknown({inst:#010x})")),
        }
    }
}

impl ScoreBoard {
    pub fn scoreboard(&mut self, dut: &VerilatorCpu) -> StepResult {
        let pc = dut.pc();
        let inst = dut.inst();
        let (mnemonic, disasm) = self.disasm(inst, pc);

        self.itrace.push(ITraceEntry::new(pc, inst, &disasm)); // itrace

        if mnemonic == "ebreak" {
            let a0 = dut.gpr(10).unwrap();
            return StepResult::EBreak(a0);
        }

        if dut.is_mmio() {
            // dtrace
            self.handle_mmio(dut, pc, inst, &mnemonic, &disasm);
        } else {
            self.golden.step().unwrap();
            if STORE_MNEMONICS.contains(&mnemonic.as_str()) {
                // mtrace
                let base_val = self.golden.gpr(rs1(inst)).unwrap();
                let addr = (base_val as i32).wrapping_add(imm_s(inst)) as u32;
                let data = self.golden.gpr(rs2(inst)).unwrap();
                let width = mem_width(&mnemonic);
                self.mtrace.push(MTraceEntry::new(
                    pc,
                    MemDir::Write,
                    addr,
                    data,
                    width,
                    &disasm,
                ));
                if !self.check_store_mem(dut, inst, &mnemonic) {
                    return StepResult::DifftestFail;
                }
            } else if LOAD_MNEMONICS.contains(&mnemonic.as_str()) {
                let base_val = self.golden.gpr(rs1(inst)).unwrap();
                let addr = (base_val as i32).wrapping_add(imm_i(inst)) as u32;
                let width = mem_width(&mnemonic);
                let data = dut.mem_load_u32(addr).unwrap_or(0); // FIXME: mtrace 仅做个记录, 但是似乎有错 ?
                self.mtrace.push(MTraceEntry::new(
                    pc,
                    MemDir::Read,
                    addr,
                    data,
                    width,
                    &disasm,
                ));
            } else if (mnemonic == "jal" || mnemonic == "jalr") && rd(inst) == 1 {
                self.ftrace.push_call(pc, dut.dnpc(), &disasm);
            } else if mnemonic == "ret" {
                self.ftrace.push_ret(pc, dut.dnpc(), &disasm);
            }
        }

        if !self.difftest(dut) {
            return StepResult::DifftestFail;
        }
        StepResult::Continue
    }

    fn handle_mmio(&mut self, dut: &VerilatorCpu, pc: u32, inst: u32, mn_str: &str, disasm: &str) {
        if LOAD_MNEMONICS.contains(&mn_str) {
            let base_val = self.golden.gpr(rs1(inst)).unwrap();
            let addr = (base_val as i32).wrapping_add(imm_i(inst)) as u32;
            let rd_idx = rd(inst);
            let data = dut.gpr(rd_idx).unwrap();

            if rd_idx != 0 {
                self.golden.set_gpr(rd_idx, data).unwrap();
            }

            self.dtrace.push(DTraceEntry::new(
                pc,
                MemDir::Read,
                addr,
                data,
                mem_width(mn_str),
                disasm,
            ));
        } else if STORE_MNEMONICS.contains(&mn_str) {
            let base_val = self.golden.gpr(rs1(inst)).unwrap();
            let addr = (base_val as i32).wrapping_add(imm_s(inst)) as u32;
            let data = self.golden.gpr(rs2(inst)).unwrap();

            self.dtrace.push(DTraceEntry::new(
                pc,
                MemDir::Write,
                addr,
                data,
                mem_width(mn_str),
                disasm,
            ));
        } else {
            panic!("unexpected MMIO instruction: {}", disasm);
        }

        self.golden.set_pc(dut.dnpc()).unwrap();
    }

    fn difftest(&self, dut: &VerilatorCpu) -> bool {
        let dut_pc = dut.dnpc();
        let ref_pc = self.golden.pc();
        if dut_pc != ref_pc {
            error!("difftest FAIL: pc  dut={dut_pc:#010x}  ref={ref_pc:#010x}");
            return false;
        }

        for i in 1..32 {
            let dut_val = dut.gpr(i).unwrap();
            let ref_val = self.golden.gpr(i).unwrap();
            if dut_val != ref_val {
                error!(
                    "difftest FAIL: {} (x{i})  dut={dut_val:#010x}  ref={ref_val:#010x}",
                    GPR_NAMES[i]
                );
                return false;
            }
        }

        let csr_checks: &[(&str, fn(&dyn AbstractCpu) -> u32)] = &[
            ("mstatus", |c| c.mstatus()),
            ("mtvec", |c| c.mtvec()),
            ("mepc", |c| c.mepc()),
            ("mcause", |c| c.mcause()),
            ("mtval", |c| c.mtval()),
        ];

        for &(name, getter) in csr_checks {
            let dut_val = getter(dut);
            let ref_val = getter(&self.golden);
            if dut_val != ref_val {
                error!("difftest FAIL: {name}  dut={dut_val:#010x}  ref={ref_val:#010x}");
                return false;
            }
        }

        true
    }

    fn check_store_mem(&self, dut: &VerilatorCpu, inst: u32, mnemonic: &str) -> bool {
        let base_val = self.golden.gpr(rs1(inst)).unwrap();
        let addr = (base_val as i32).wrapping_add(imm_s(inst)) as u32;
        let width = mem_width(mnemonic);

        match width {
            1 => {
                let dut_val = dut.mem_load_u8(addr).unwrap();
                let ref_val = self.golden.mem_load_u8(addr).unwrap();
                if dut_val != ref_val {
                    error!(
                        "difftest FAIL: mem[{addr:#010x}] (u8)  dut={dut_val:#04x}  ref={ref_val:#04x}"
                    );
                    return false;
                }
            }
            2 => {
                let dut_val = dut.mem_load_u16(addr).unwrap();
                let ref_val = self.golden.mem_load_u16(addr).unwrap();
                if dut_val != ref_val {
                    error!(
                        "difftest FAIL: mem[{addr:#010x}] (u16)  dut={dut_val:#06x}  ref={ref_val:#06x}"
                    );
                    return false;
                }
            }
            4 => {
                let dut_val = dut.mem_load_u32(addr).unwrap();
                let ref_val = self.golden.mem_load_u32(addr).unwrap();
                if dut_val != ref_val {
                    error!(
                        "difftest FAIL: mem[{addr:#010x}] (u32)  dut={dut_val:#010x}  ref={ref_val:#010x}"
                    );
                    return false;
                }
            }
            _ => {}
        }
        true
    }

    pub fn dump_traces(&self, dut: &VerilatorCpu) {
        error!(
            "===== ITrace (recent {} instructions) =====",
            TRACE_CAPACITY
        );
        error!("{}", self.itrace.dump());

        error!(
            "===== DTrace (recent {} MMIO accesses) =====",
            TRACE_CAPACITY
        );
        error!("{}", self.dtrace.dump());

        error!("===== Register State =====");
        error!("       {:>12}  {:>12}", "DUT", "REF");
        error!(
            "pc     {:#012x}  {:#012x}{}",
            dut.dnpc(),
            self.golden.pc(),
            if dut.dnpc() != self.golden.pc() {
                "  <--- MISMATCH"
            } else {
                ""
            }
        );
        for i in 1..32 {
            let d = dut.gpr(i).unwrap();
            let r = self.golden.gpr(i).unwrap();
            let mark = if d != r { "  <--- MISMATCH" } else { "" };
            error!("{:4}   {d:#012x}  {r:#012x}{mark}", GPR_NAMES[i]);
        }

        error!("===== FTrace (recent calls) =====");
        error!("{}", self.ftrace.ring_buf.dump());
    }
}
