use std::fmt::Write;

use log::{error, info, warn};

use rustyline::DefaultEditor;

use crate::common::lightsss::{ForkResult, LightSSS};
use crate::libcpu::abstract_cpu::AbstractCpu;
use crate::libcpu::verilator::cpu::VerilatorCpu;
use crate::libsdb::scoreboard::{ScoreBoard, StepResult};
use crate::libsdb::watchpoint::WatchpointPool;
use crate::libsdb::{command, expression};

const GPR_NAMES: &[&str] = &[
    "$0", "ra", "sp", "gp", "tp", "t0", "t1", "t2", "s0", "s1", "a0", "a1", "a2", "a3", "a4", "a5",
    "a6", "a7", "s2", "s3", "s4", "s5", "s6", "s7", "s8", "s9", "s10", "s11", "t3", "t4", "t5",
    "t6",
];

enum Action {
    Continue,
    Quit,
}

enum SdbError {
    Input(String),
    Fatal(miette::Error),
}

type CmdResult = Result<Action, SdbError>;

struct CommandDef {
    names: &'static [&'static str],
    help: &'static str,
    func: fn(&str, &mut Sdb, &mut VerilatorCpu) -> CmdResult,
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

#[allow(unused)]
pub struct Sdb<'a> {
    breakpoints: Vec<u32>,
    watchpoints: WatchpointPool,
    last_cmd: Option<String>,
    scoreboard: &'a mut ScoreBoard,
    lightsss: Option<LightSSS>,
}

impl<'a> Sdb<'a> {
    pub fn new(scoreboard: &'a mut ScoreBoard, enable_fork: bool) -> Self {
        let lightsss = if enable_fork {
            info!("[lightsss] enabled");
            Some(LightSSS::new())
        } else {
            None
        };
        Self {
            breakpoints: Vec::new(),
            watchpoints: WatchpointPool::new(),
            last_cmd: None,
            scoreboard,
            lightsss,
        }
    }

    pub fn mainloop(&mut self, dut: &mut VerilatorCpu, batch: bool) -> miette::Result<()> {
        // generate a checkpoint at the start time
        if let Some(ref mut lightsss) = self.lightsss {
            match lightsss.do_fork() {
                ForkResult::Ok => {}
                ForkResult::Child { end_cycles } => child_run_to_end(dut, end_cycles),
            }
        }

        if batch {
            match self.execute_steps(usize::MAX, dut) {
                Ok(Action::Continue) => {}
                Ok(Action::Quit) => return Ok(()),
                Err(SdbError::Input(_)) => unreachable!(),
                Err(SdbError::Fatal(e)) => return Err(e),
            }
        }

        let mut rl = DefaultEditor::new().expect("failed to create readline editor");

        loop {
            match rl.readline("(sdb) ") {
                Ok(line) => {
                    let line = line.trim().to_string();
                    let input = if line.is_empty() {
                        match &self.last_cmd {
                            Some(prev) => prev.clone(),
                            None => continue,
                        }
                    } else {
                        let _ = rl.add_history_entry(&line);
                        self.last_cmd = Some(line.clone());
                        line
                    };
                    match self.execute_line(&input, dut) {
                        Ok(Action::Continue) => {}
                        Ok(Action::Quit) => return Ok(()),
                        Err(SdbError::Input(msg)) => warn!("{msg}"),
                        Err(SdbError::Fatal(e)) => return Err(e),
                    }
                }
                Err(rustyline::error::ReadlineError::Eof) => return Ok(()),
                Err(e) => {
                    error!("readline error: {e}");
                }
            }
        }
    }

    fn execute_line(&mut self, input: &str, dut: &mut VerilatorCpu) -> CmdResult {
        let Some(cmd) = command::parse(input) else {
            return Ok(Action::Continue);
        };

        for def in COMMANDS {
            if def.names.contains(&cmd.name.as_str()) {
                return (def.func)(&cmd.args, self, dut);
            }
        }

        Err(SdbError::Input(format!("unknown command: {}", cmd.name)))
    }

    fn execute_steps(&mut self, n: usize, dut: &mut VerilatorCpu) -> CmdResult {

        for _ in 0..n {
            if let Some(ref mut lightsss) = self.lightsss {
                if lightsss.should_fork() {
                    match lightsss.do_fork() {
                        ForkResult::Ok => {}
                        ForkResult::Child { end_cycles } => child_run_to_end(dut, end_cycles),
                    }
                }
            }

            dut.step().map_err(|e| SdbError::Fatal(e))?;

            match self.scoreboard.scoreboard(dut) {
                StepResult::Continue => {}
                StepResult::EBreak(a0) => {
                    if a0 == 0 {
                        info!("program exited successfully");
                        return Ok(Action::Quit);
                    } else {
                        return Err(SdbError::Fatal(miette::miette!(
                            "program exited with failure (a0 = {a0:#x})"
                        )));
                    }
                }
                StepResult::DifftestFail => {
                    return Err(SdbError::Fatal(miette::miette!("difftest failed")));
                }
            }
            if self.check_breakpoints(dut) {
                return Ok(Action::Continue);
            }
            let mut buf = String::new();
            if self.watchpoints.check(dut, &mut buf) {
                return Ok(Action::Continue);
            }
        }
        Ok(Action::Continue)
    }

    pub fn lightsss_on_error(&mut self, dut: &VerilatorCpu) {
        if let Some(ref mut lightsss) = self.lightsss {
            if !lightsss.is_child() {
                // parent crashed,
                lightsss.wakeup_child(dut.sim_time());
            }
        }
    }

    /// @return: hit a breakpoint -> true
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

fn cmd_help(_args: &str, _sdb: &mut Sdb, _cpu: &mut VerilatorCpu) -> CmdResult {
    let mut buf = String::from("Commands:\n");
    for def in COMMANDS {
        let names = def.names.join(", ");
        let _ = writeln!(buf, "  {names:20} {}", def.help);
    }
    info!("{buf}");
    Ok(Action::Continue)
}

fn cmd_quit(_args: &str, _sdb: &mut Sdb, _cpu: &mut VerilatorCpu) -> CmdResult {
    Ok(Action::Quit)
}

fn cmd_continue(_args: &str, sdb: &mut Sdb, dut: &mut VerilatorCpu) -> CmdResult {
    sdb.execute_steps(usize::MAX, dut)
}

fn cmd_step(args: &str, sdb: &mut Sdb, dut: &mut VerilatorCpu) -> CmdResult {
    let n: usize = if args.is_empty() {
        1
    } else {
        args.trim()
            .parse()
            .map_err(|_| SdbError::Input("usage: step [N]".into()))?
    };
    sdb.execute_steps(n, dut)
}

fn cmd_info(args: &str, sdb: &mut Sdb, dut: &mut VerilatorCpu) -> CmdResult {
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
        _ => return Err(SdbError::Input("usage: info r|w|b".into())),
    }
    Ok(Action::Continue)
}

fn cmd_examine(args: &str, _sdb: &mut Sdb, dut: &mut VerilatorCpu) -> CmdResult {
    let parts: Vec<&str> = args.splitn(2, char::is_whitespace).collect();
    if parts.len() < 2 {
        return Err(SdbError::Input("usage: x N EXPR".into()));
    }
    let n: usize = parts[0]
        .trim()
        .parse()
        .map_err(|_| SdbError::Input(format!("bad count: {}", parts[0])))?;
    let addr = expression::eval(parts[1].trim(), dut)
        .map_err(|e| SdbError::Input(format!("expression error: {e}")))?;

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
    Ok(Action::Continue)
}

fn cmd_print(args: &str, _sdb: &mut Sdb, dut: &mut VerilatorCpu) -> CmdResult {
    if args.trim().is_empty() {
        return Err(SdbError::Input("usage: p EXPR".into()));
    }
    let val = expression::eval(args.trim(), dut)
        .map_err(|e| SdbError::Input(format!("expression error: {e}")))?;
    info!("{val:#010x} ({val})");
    Ok(Action::Continue)
}

fn cmd_watch(args: &str, sdb: &mut Sdb, dut: &mut VerilatorCpu) -> CmdResult {
    let expr = args.trim();
    if expr.is_empty() {
        return Err(SdbError::Input("usage: w EXPR".into()));
    }
    let id = sdb
        .watchpoints
        .add(expr, dut)
        .map_err(|e| SdbError::Input(format!("expression error: {e}")))?;
    info!("watchpoint #{id}: {expr}");
    Ok(Action::Continue)
}

fn cmd_delete(args: &str, sdb: &mut Sdb, _cpu: &mut VerilatorCpu) -> CmdResult {
    let id: usize = args
        .trim()
        .parse()
        .map_err(|_| SdbError::Input("usage: d N".into()))?;
    if sdb.watchpoints.remove(id) {
        info!("deleted watchpoint #{id}");
    } else {
        return Err(SdbError::Input(format!("watchpoint #{id} not found")));
    }
    Ok(Action::Continue)
}

fn cmd_break(args: &str, sdb: &mut Sdb, dut: &mut VerilatorCpu) -> CmdResult {
    let expr = args.trim();
    if expr.is_empty() {
        return Err(SdbError::Input("usage: b ADDR".into()));
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
            return Ok(Action::Continue);
        }
        "rm" | "remove" => {
            let rest = expr[sub.len()..].trim();
            let idx: usize = match rest.parse::<usize>() {
                Ok(v) if v >= 1 && v <= sdb.breakpoints.len() => v - 1,
                _ => return Err(SdbError::Input("usage: b rm N".into())),
            };
            let addr = sdb.breakpoints.remove(idx);
            info!("deleted breakpoint #{} at {addr:#010x}", idx + 1);
            return Ok(Action::Continue);
        }
        _ => {}
    }

    let addr = expression::eval(expr, dut)
        .map_err(|e| SdbError::Input(format!("expression error: {e}")))?;
    sdb.breakpoints.push(addr);
    info!("breakpoint #{} at {addr:#010x}", sdb.breakpoints.len());
    Ok(Action::Continue)
}

fn child_run_to_end(dut: &mut VerilatorCpu, end_cycles: u64) {
    dut.enable_wave();
    info!(
        "[lightsss] child dumping wave from {} to {end_cycles}...",
        dut.sim_time()
    );
    if let Err(e) = dut.run_until(end_cycles) {
        info!("[lightsss] child replay stopped early: {e}");
    }
    dut.flush_wave();
    info!("[lightsss] child wave dump finished, exiting");
    std::process::exit(0);
}
