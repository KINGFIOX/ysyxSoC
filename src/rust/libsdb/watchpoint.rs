use std::fmt;

use crate::libcpu::abstract_cpu::AbstractCpu;
use crate::libsdb::expression;
use crate::libsdb::expression::Token;

pub struct Watchpoint {
    pub id: usize,
    pub expr_str: String,
    pub tokens: Vec<Token>,
    pub last_value: u64,
}

pub struct WatchpointPool {
    watchpoints: Vec<Watchpoint>,
    next_id: usize,
}

impl WatchpointPool {
    pub fn new() -> Self {
        Self {
            watchpoints: Vec::new(),
            next_id: 1,
        }
    }

    pub fn add(&mut self, expr_str: &str, cpu: &dyn AbstractCpu) -> miette::Result<usize> {
        let tokens = expression::tokenize(expr_str)?;
        let val = expression::eval_tokens(&tokens, cpu)?;
        let id = self.next_id;
        self.next_id += 1;
        self.watchpoints.push(Watchpoint {
            id,
            expr_str: expr_str.to_string(),
            tokens,
            last_value: val,
        });
        Ok(id)
    }

    pub fn remove(&mut self, id: usize) -> bool {
        if let Some(pos) = self.watchpoints.iter().position(|wp| wp.id == id) {
            self.watchpoints.remove(pos);
            true
        } else {
            false
        }
    }

    pub fn is_empty(&self) -> bool {
        self.watchpoints.is_empty()
    }

    /// Re-evaluate all watchpoints. Returns true if any triggered (value changed).
    pub fn check(&mut self, cpu: &dyn AbstractCpu, out: &mut dyn fmt::Write) -> bool {
        let mut triggered = false;

        for wp in &mut self.watchpoints {
            match expression::eval_tokens(&wp.tokens, cpu) {
                Ok(new_val) => {
                    if new_val != wp.last_value {
                        let _ = writeln!(
                            out,
                            "watchpoint {}: {} changed from {:#018x} to {:#018x}",
                            wp.id, wp.expr_str, wp.last_value, new_val
                        );
                        wp.last_value = new_val;
                        triggered = true;
                    }
                }
                Err(e) => {
                    let _ = writeln!(out, "watchpoint {}: eval error: {e}", wp.id);
                }
            }
        }

        triggered
    }

    pub fn list(&self, out: &mut dyn fmt::Write) {
        if self.watchpoints.is_empty() {
            let _ = writeln!(out, "no watchpoints");
            return;
        }
        for wp in &self.watchpoints {
            let _ = writeln!(
                out,
                "  #{}: {} = {:#018x}",
                wp.id, wp.expr_str, wp.last_value
            );
        }
    }
}
