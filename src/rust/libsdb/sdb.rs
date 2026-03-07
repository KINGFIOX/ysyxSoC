use std::fmt::Write;
use std::panic;
use std::sync::Arc;
use std::sync::atomic::{AtomicBool, Ordering};

use log::{error, info, warn};

use rustyline::DefaultEditor;

use crate::libcpu::abstract_cpu::AbstractCpu;
use crate::libcpu::verilator::cpu::VerilatorCpu;
use crate::libsdb::{command, expression};
use crate::libsdb::scoreboard::{ScoreBoard, StepResult};
use crate::libsdb::watchpoint::WatchpointPool;

struct SigIntGuard {
    flag: Arc<AtomicBool>,
    sig_id: signal_hook::SigId,
}

impl SigIntGuard {
    fn new() -> Self {
        let flag = Arc::new(AtomicBool::new(false));
        let sig_id = signal_hook::flag::register(signal_hook::consts::SIGINT, Arc::clone(&flag))
            .expect("failed to register SIGINT handler");
        Self { flag, sig_id }
    }

    fn interrupted(&self) -> bool {
        self.flag.load(Ordering::Relaxed)
    }
}

impl Drop for SigIntGuard {
    fn drop(&mut self) {
        signal_hook::low_level::unregister(self.sig_id);
    }
}

const GPR_NAMES: &[&str] = &[
    "$0", "ra", "sp", "gp", "tp", "t0", "t1", "t2", "s0", "s1", "a0", "a1", "a2", "a3", "a4", "a5",
    "a6", "a7", "s2", "s3", "s4", "s5", "s6", "s7", "s8", "s9", "s10", "s11", "t3", "t4", "t5",
    "t6",
];

struct CommandDef {
    names: &'static [&'static str],
    help: &'static str,
    func: fn(&str, &mut Sdb, &mut VerilatorCpu),
}

const COMMANDS: &[CommandDef] = &[
    CommandDef {
        names: &["help", "h"],
        help: "show this help message",
        func: cmd_help,
    },
    CommandDef {
        names: &["quit", "q"],
        help: "quit the debugger",
        func: cmd_quit,
    },
    CommandDef {
        names: &["continue", "c"],
        help: "continue execution",
        func: cmd_continue,
    },
    CommandDef {
        names: &["step", "si", "s"],
        help: "step N instructions (default 1)",
        func: cmd_step,
    },
    CommandDef {
        names: &["info"],
        help: "info r(egisters) / info w(atchpoints)",
        func: cmd_info,
    },
    CommandDef {
        names: &["examine", "x"],
        help: "x N EXPR - examine N words at EXPR",
        func: cmd_examine,
    },
    CommandDef {
        names: &["print", "p", "eval"],
        help: "evaluate expression",
        func: cmd_print,
    },
    CommandDef {
        names: &["watch", "w"],
        help: "add watchpoint on expression",
        func: cmd_watch,
    },
    CommandDef {
        names: &["delete", "d"],
        help: "delete watchpoint by id",
        func: cmd_delete,
    },
    CommandDef {
        names: &["break", "b"],
        help: "set breakpoint at address",
        func: cmd_break,
    },
];

#[derive(PartialEq)]
enum State {
    Stop,
    Running,
    Quit,
    #[allow(unused)]
    Abort,
}

#[allow(unused)]
pub struct Sdb<'a> {
    breakpoints: Vec<u32>,
    watchpoints: WatchpointPool,
    state: State,
    last_cmd: Option<String>,
    scoreboard: &'a mut ScoreBoard,
}

impl<'a> Sdb<'a> {
    pub fn new(scoreboard: &'a mut ScoreBoard) -> Self {
        Self {
            breakpoints: Vec::new(),
            watchpoints: WatchpointPool::new(),
            state: State::Stop,
            last_cmd: None,
            scoreboard,
        }
    }

    pub fn mainloop(&mut self, dut: &mut VerilatorCpu, batch: bool) -> miette::Result<()> {
        if batch {
            self.execute_steps(usize::MAX, dut);
            match self.state {
                State::Stop => { /*do nothing*/ }
                State::Running => panic!("impossible"),
                State::Quit => return Ok(()),
                State::Abort => return Err(miette::Error::msg("abort")),
            }
        }

        let mut rl = DefaultEditor::new().expect("failed to create readline editor");

        loop {
            // check state after executing a line
            match self.state {
                State::Stop => { /*continue*/ }
                State::Running => panic!("impossible to be in running state"),
                State::Quit => return Ok(()),
                State::Abort => return Err(miette::Error::msg("abort")),
            }
            match rl.readline("(sdb) ") {
                Ok(line) => {
                    let line = line.trim().to_string();
                    let input = if line.is_empty() {
                        match &self.last_cmd {
                            Some(prev) => prev.clone(), // last command
                            None => continue,
                        }
                    } else {
                        let _ = rl.add_history_entry(&line);
                        self.last_cmd = Some(line.clone());
                        line
                    };
                    self.execute_line(&input, dut); // this will change the state of the Sdb
                }
                Err(rustyline::error::ReadlineError::Eof) => {
                    self.state = State::Quit;
                }
                Err(e) => {
                    error!("readline error: {e}");
                }
            }
        }
    }

    fn execute_line(&mut self, input: &str, dut: &mut VerilatorCpu) {
        let Some(cmd) = command::parse(input) else {
            return;
        };

        for def in COMMANDS {
            if def.names.contains(&cmd.name.as_str()) {
                (def.func)(&cmd.args, self, dut); // this will change the state of the Sdb
                return;
            }
        }

        warn!("unknown command: {}", cmd.name);
    }

    fn execute_steps(&mut self, n: usize, dut: &mut VerilatorCpu) {
        self.state = State::Running;
        let guard = SigIntGuard::new();

        for _ in 0..n {
            if guard.interrupted() {
                self.state = State::Stop;
                return;
            }
            if let Err(e) = dut.step() {
                self.state = State::Abort;
                error!("step error: {e}");
                return;
            }
            match self.scoreboard.scoreboard(dut) {
                StepResult::Continue => {}
                StepResult::EBreak(a0) => {
                    if a0 == 0 {
                        info!("program exited successfully");
                        self.state = State::Quit;
                    } else {
                        error!("program exited with failure (a0 = {a0:#x})");
                        self.state = State::Abort;
                    }
                    return;
                }
                StepResult::DifftestFail => {
                    self.state = State::Abort;
                    error!("difftest failed");
                    return;
                }
            }
            if self.check_breakpoints(dut) {
                self.state = State::Stop;
                return;
            }
            let mut buf = String::new();
            if self.watchpoints.check(dut, &mut buf) {
                self.state = State::Stop;
                return;
            }
        }
        self.state = State::Stop; // normal exit
    }

    fn check_breakpoints(&self, dut: &VerilatorCpu) -> bool {
        let pc = dut.pc();
        for &bp in &self.breakpoints {
            if pc == bp {
                info!("breakpoint hit at {pc:#010x}");
                return true;
            }
        }
        false
    }
}

fn cmd_help(_args: &str, _sdb: &mut Sdb, _cpu: &mut VerilatorCpu) {
    let mut buf = String::from("Commands:\n");
    for def in COMMANDS {
        let names = def.names.join(", ");
        let _ = writeln!(buf, "  {names:20} {}", def.help);
    }
    info!("{buf}");
}

fn cmd_quit(_args: &str, sdb: &mut Sdb, _cpu: &mut VerilatorCpu) {
    sdb.state = State::Quit;
}

fn cmd_continue(_args: &str, sdb: &mut Sdb, dut: &mut VerilatorCpu) {
    sdb.execute_steps(usize::MAX, dut); // this will change the state of the Sdb
    assert!(sdb.state != State::Running)
}

fn cmd_step(args: &str, sdb: &mut Sdb, dut: &mut VerilatorCpu) {
    let n: usize = if args.is_empty() {
        1
    } else {
        match args.trim().parse() {
            Ok(v) => v,
            Err(_) => {
                warn!("usage: step [N]");
                return;
            }
        }
    };
    sdb.execute_steps(n, dut);
    assert!(sdb.state != State::Running)
}

fn cmd_info(args: &str, sdb: &mut Sdb, dut: &mut VerilatorCpu) {
    let sub = args.trim();
    match sub {
        "r" | "registers" | "reg" => {
            let mut buf = format!("pc  = {:#010x}\n", dut.pc());
            for i in 0..32 {
                let _ = write!(buf, "{:4} = {:#010x}  ", GPR_NAMES[i], dut.gpr(i).unwrap());
                if (i + 1) % 4 == 0 {
                    buf.push('\n');
                }
            }
            info!("{buf}");
        }
        "w" | "watchpoints" | "wp" => {
            let mut buf = String::new();
            sdb.watchpoints.list(&mut buf);
            info!("{buf}");
        }
        "b" | "breakpoints" | "bp" => {
            if sdb.breakpoints.is_empty() {
                info!("no breakpoints");
            } else {
                let mut buf = String::new();
                for (i, &bp) in sdb.breakpoints.iter().enumerate() {
                    let _ = writeln!(buf, "  #{}: {bp:#010x}", i + 1);
                }
                info!("{buf}");
            }
        }
        _ => warn!("usage: info r|w|b"),
    }
}

fn cmd_examine(args: &str, _sdb: &mut Sdb, dut: &mut VerilatorCpu) {
    let parts: Vec<&str> = args.splitn(2, char::is_whitespace).collect();
    if parts.len() < 2 {
        warn!("usage: x N EXPR");
        return;
    }
    let n: usize = match parts[0].trim().parse() {
        Ok(v) => v,
        Err(_) => {
            warn!("bad count: {}", parts[0]);
            return;
        }
    };
    let addr = match expression::eval(parts[1].trim(), dut) {
        Ok(v) => v,
        Err(e) => {
            error!("expression error: {e}");
            return;
        }
    };

    let mut buf = String::new();
    for i in 0..n {
        let a = addr.wrapping_add((i as u32) * 4);
        if i % 4 == 0 {
            let _ = write!(buf, "{a:#010x}:");
        }
        match dut.mem_load_u32(a) {
            Ok(val) => {
                let _ = write!(buf, "  {val:#010x}");
            }
            Err(_) => {
                let _ = write!(buf, "  ??????????");
            }
        }
        if (i + 1) % 4 == 0 || i + 1 == n {
            buf.push('\n');
        }
    }
    info!("{buf}");
}

fn cmd_print(args: &str, _sdb: &mut Sdb, dut: &mut VerilatorCpu) {
    if args.trim().is_empty() {
        warn!("usage: p EXPR");
        return;
    }
    match expression::eval(args.trim(), dut) {
        Ok(val) => info!("{val:#010x} ({val})"),
        Err(e) => error!("expression error: {e}"),
    }
}

fn cmd_watch(args: &str, sdb: &mut Sdb, dut: &mut VerilatorCpu) {
    let expr = args.trim();
    if expr.is_empty() {
        warn!("usage: w EXPR");
        return;
    }
    match sdb.watchpoints.add(expr, dut) {
        Ok(id) => info!("watchpoint #{id}: {expr}"),
        Err(e) => error!("expression error: {e}"),
    }
}

fn cmd_delete(args: &str, sdb: &mut Sdb, _cpu: &mut VerilatorCpu) {
    let id: usize = match args.trim().parse() {
        Ok(v) => v,
        Err(_) => {
            warn!("usage: d N");
            return;
        }
    };
    if sdb.watchpoints.remove(id) {
        info!("deleted watchpoint #{id}");
    } else {
        warn!("watchpoint #{id} not found");
    }
}

fn cmd_break(args: &str, sdb: &mut Sdb, dut: &mut VerilatorCpu) {
    let expr = args.trim();
    if expr.is_empty() {
        warn!("usage: b ADDR");
        return;
    }

    let sub = expr.split_whitespace().next().unwrap();
    match sub {
        "ls" | "list" => {
            if sdb.breakpoints.is_empty() {
                info!("no breakpoints");
            } else {
                let mut buf = String::new();
                for (i, &bp) in sdb.breakpoints.iter().enumerate() {
                    let _ = writeln!(buf, "  #{}: {bp:#010x}", i + 1);
                }
                info!("{buf}");
            }
            return;
        }
        "rm" | "remove" => {
            let rest = expr[sub.len()..].trim();
            let idx: usize = match rest.parse::<usize>() {
                Ok(v) if v >= 1 && v <= sdb.breakpoints.len() => v - 1,
                _ => {
                    warn!("usage: b rm N");
                    return;
                }
            };
            let addr = sdb.breakpoints.remove(idx);
            info!("deleted breakpoint #{} at {addr:#010x}", idx + 1);
            return;
        }
        _ => {}
    }

    match expression::eval(expr, dut) {
        Ok(addr) => {
            sdb.breakpoints.push(addr);
            info!("breakpoint #{} at {addr:#010x}", sdb.breakpoints.len());
        }
        Err(e) => error!("expression error: {e}"),
    }
}
