use crate::libcpu::AbstractCpu;
use rustyline::DefaultEditor;

use super::command;
use super::expression;
use super::watchpoint::WatchpointPool;

const GPR_NAMES: &[&str] = &[
    "$0", "ra", "sp", "gp", "tp", "t0", "t1", "t2", "s0", "s1", "a0", "a1", "a2", "a3", "a4", "a5",
    "a6", "a7", "s2", "s3", "s4", "s5", "s6", "s7", "s8", "s9", "s10", "s11", "t3", "t4", "t5",
    "t6",
];

struct CommandDef {
    names: &'static [&'static str],
    help: &'static str,
    func: fn(&str, &mut Sdb, &mut dyn AbstractCpu) -> Action,
}

#[derive(PartialEq)]
enum Action {
    Continue,
    Quit,
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

pub struct Sdb {
    breakpoints: Vec<u32>,
    watchpoints: WatchpointPool,
    stopped: bool,
    last_cmd: Option<String>,
}

impl Sdb {
    pub fn new() -> Self {
        Self {
            breakpoints: Vec::new(),
            watchpoints: WatchpointPool::new(),
            stopped: false,
            last_cmd: None,
        }
    }

    pub fn mainloop(&mut self, cpu: &mut dyn AbstractCpu) {
        let mut rl = DefaultEditor::new().expect("failed to create readline editor");

        while !self.stopped {
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
                    self.execute_line(&input, cpu);
                }
                Err(rustyline::error::ReadlineError::Eof) => {
                    self.stopped = true;
                }
                Err(e) => {
                    eprintln!("readline error: {e}");
                    self.stopped = true;
                }
            }
        }
    }

    fn execute_line(&mut self, input: &str, cpu: &mut dyn AbstractCpu) {
        let Some(cmd) = command::parse(input) else {
            return;
        };

        for def in COMMANDS {
            if def.names.contains(&cmd.name.as_str()) {
                let action = (def.func)(&cmd.args, self, cpu);
                if action == Action::Quit {
                    self.stopped = true;
                }
                return;
            }
        }

        eprintln!("unknown command: {}", cmd.name);
    }

    fn execute_steps(&mut self, n: usize, cpu: &mut dyn AbstractCpu) {
        for _ in 0..n {
            cpu.step();

            if self.check_breakpoints(cpu) {
                return;
            }

            let mut buf = String::new();
            if self.watchpoints.check(cpu, &mut buf) {
                print!("{buf}");
                return;
            }
        }
    }

    fn check_breakpoints(&self, cpu: &dyn AbstractCpu) -> bool {
        let pc = cpu.pc();
        for &bp in &self.breakpoints {
            if pc == bp {
                println!("breakpoint hit at {pc:#010x}");
                return true;
            }
        }
        false
    }
}

fn cmd_help(_args: &str, _sdb: &mut Sdb, _cpu: &mut dyn AbstractCpu) -> Action {
    println!("Commands:");
    for def in COMMANDS {
        let names = def.names.join(", ");
        println!("  {names:20} {}", def.help);
    }
    Action::Continue
}

fn cmd_quit(_args: &str, _sdb: &mut Sdb, _cpu: &mut dyn AbstractCpu) -> Action {
    Action::Quit
}

fn cmd_continue(_args: &str, sdb: &mut Sdb, cpu: &mut dyn AbstractCpu) -> Action {
    loop {
        cpu.step();
        if sdb.check_breakpoints(cpu) {
            break;
        }
        let mut buf = String::new();
        if sdb.watchpoints.check(cpu, &mut buf) {
            print!("{buf}");
            break;
        }
    }
    Action::Continue
}

fn cmd_step(args: &str, sdb: &mut Sdb, cpu: &mut dyn AbstractCpu) -> Action {
    let n: usize = if args.is_empty() {
        1
    } else {
        match args.trim().parse() {
            Ok(v) => v,
            Err(_) => {
                eprintln!("usage: step [N]");
                return Action::Continue;
            }
        }
    };
    sdb.execute_steps(n, cpu);
    Action::Continue
}

fn cmd_info(args: &str, sdb: &mut Sdb, cpu: &mut dyn AbstractCpu) -> Action {
    let sub = args.trim();
    match sub {
        "r" | "registers" | "reg" => {
            println!("pc  = {:#010x}", cpu.pc());
            for i in 0..32 {
                print!("{:4} = {:#010x}  ", GPR_NAMES[i], cpu.gpr(i).unwrap());
                if (i + 1) % 4 == 0 {
                    println!();
                }
            }
        }
        "w" | "watchpoints" | "wp" => {
            let mut buf = String::new();
            sdb.watchpoints.list(&mut buf);
            print!("{buf}");
        }
        "b" | "breakpoints" | "bp" => {
            if sdb.breakpoints.is_empty() {
                println!("no breakpoints");
            } else {
                for (i, &bp) in sdb.breakpoints.iter().enumerate() {
                    println!("  #{}: {bp:#010x}", i + 1);
                }
            }
        }
        _ => eprintln!("usage: info r|w|b"),
    }
    Action::Continue
}

fn cmd_examine(args: &str, _sdb: &mut Sdb, cpu: &mut dyn AbstractCpu) -> Action {
    let parts: Vec<&str> = args.splitn(2, char::is_whitespace).collect();
    if parts.len() < 2 {
        eprintln!("usage: x N EXPR");
        return Action::Continue;
    }
    let n: usize = match parts[0].trim().parse() {
        Ok(v) => v,
        Err(_) => {
            eprintln!("bad count: {}", parts[0]);
            return Action::Continue;
        }
    };
    let addr = match expression::eval(parts[1].trim(), cpu) {
        Ok(v) => v,
        Err(e) => {
            eprintln!("expression error: {e}");
            return Action::Continue;
        }
    };

    for i in 0..n {
        let a = addr.wrapping_add((i as u32) * 4);
        if i % 4 == 0 {
            print!("{a:#010x}:");
        }
        match cpu.mem_load_u32(a) {
            Ok(val) => print!("  {val:#010x}"),
            Err(_) => print!("  ??????????"),
        }
        if (i + 1) % 4 == 0 || i + 1 == n {
            println!();
        }
    }
    Action::Continue
}

fn cmd_print(args: &str, _sdb: &mut Sdb, cpu: &mut dyn AbstractCpu) -> Action {
    if args.trim().is_empty() {
        eprintln!("usage: p EXPR");
        return Action::Continue;
    }
    match expression::eval(args.trim(), cpu) {
        Ok(val) => println!("{val:#010x} ({val})"),
        Err(e) => eprintln!("expression error: {e}"),
    }
    Action::Continue
}

fn cmd_watch(args: &str, sdb: &mut Sdb, cpu: &mut dyn AbstractCpu) -> Action {
    let expr = args.trim();
    if expr.is_empty() {
        eprintln!("usage: w EXPR");
        return Action::Continue;
    }
    match sdb.watchpoints.add(expr, cpu) {
        Ok(id) => println!("watchpoint #{id}: {expr}"),
        Err(e) => eprintln!("expression error: {e}"),
    }
    Action::Continue
}

fn cmd_delete(args: &str, sdb: &mut Sdb, _cpu: &mut dyn AbstractCpu) -> Action {
    let id: usize = match args.trim().parse() {
        Ok(v) => v,
        Err(_) => {
            eprintln!("usage: d N");
            return Action::Continue;
        }
    };
    if sdb.watchpoints.remove(id) {
        println!("deleted watchpoint #{id}");
    } else {
        eprintln!("watchpoint #{id} not found");
    }
    Action::Continue
}

fn cmd_break(args: &str, sdb: &mut Sdb, cpu: &mut dyn AbstractCpu) -> Action {
    let expr = args.trim();
    if expr.is_empty() {
        eprintln!("usage: b ADDR");
        return Action::Continue;
    }

    let sub = expr.split_whitespace().next().unwrap();
    match sub {
        "ls" | "list" => {
            if sdb.breakpoints.is_empty() {
                println!("no breakpoints");
            } else {
                for (i, &bp) in sdb.breakpoints.iter().enumerate() {
                    println!("  #{}: {bp:#010x}", i + 1);
                }
            }
            return Action::Continue;
        }
        "rm" | "remove" => {
            let rest = expr[sub.len()..].trim();
            let idx: usize = match rest.parse::<usize>() {
                Ok(v) if v >= 1 && v <= sdb.breakpoints.len() => v - 1,
                _ => {
                    eprintln!("usage: b rm N");
                    return Action::Continue;
                }
            };
            let addr = sdb.breakpoints.remove(idx);
            println!("deleted breakpoint #{} at {addr:#010x}", idx + 1);
            return Action::Continue;
        }
        _ => {}
    }

    match expression::eval(expr, cpu) {
        Ok(addr) => {
            sdb.breakpoints.push(addr);
            println!("breakpoint #{} at {addr:#010x}", sdb.breakpoints.len());
        }
        Err(e) => eprintln!("expression error: {e}"),
    }
    Action::Continue
}
