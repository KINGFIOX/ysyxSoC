#![allow(unused_imports)]
use super::*;

use crate::libcpu::AbstractCpu;

// ── Tokenizer ──

pub enum Token {
    Num(u32),
    Reg(String),
    Plus,
    Minus,
    Star,
    Slash,
    Eq,
    Ne,
    Lt,
    Le,
    Gt,
    Ge,
    And,
    Or,
    LParen,
    RParen,
}

pub fn tokenize(input: &str) -> miette::Result<Vec<Token>> {
    let mut tokens = Vec::new();
    let mut s = input;
    while {
        s = s.trim_start();
        !s.is_empty()
    } {
        if let Some(rest) = s.strip_prefix("0x").or_else(|| s.strip_prefix("0X")) {
            let end = rest
                .find(|c: char| !c.is_ascii_hexdigit()) // first non-hex digit
                .unwrap_or(rest.len());
            if end == 0 {
                return Err(miette::Error::msg(format!(
                    "invalid hex literal: {}",
                    &s[..2]
                )));
            }
            let v = u32::from_str_radix(&rest[..end], 16)
                .map_err(|e| miette::Error::msg(format!("bad hex number: {e}")))?;
            tokens.push(Token::Num(v));
            s = &rest[end..];
            continue;
        }

        if s.starts_with(|c: char| c.is_ascii_digit()) {
            let end = s.find(|c: char| !c.is_ascii_digit()).unwrap_or(s.len());
            let v: u32 = s[..end]
                .parse()
                .map_err(|e| miette::Error::msg(format!("bad number: {e}")))?;
            tokens.push(Token::Num(v));
            s = &s[end..];
            continue;
        }

        if let Some(rest) = s.strip_prefix('$') {
            let end = rest
                .find(|c: char| !c.is_alphanumeric() && c != '_')
                .unwrap_or(rest.len());
            if end == 0 {
                return Err(miette::Error::msg("expected register name after $"));
            }
            tokens.push(Token::Reg(rest[..end].to_string()));
            s = &rest[end..];
            continue;
        }

        if s.len() >= 2 {
            let tok = match &s[..2] {
                "==" => Some(Token::Eq),
                "!=" => Some(Token::Ne),
                "<=" => Some(Token::Le),
                ">=" => Some(Token::Ge),
                "&&" => Some(Token::And),
                "||" => Some(Token::Or),
                _ => None,
            };
            if let Some(tok) = tok {
                tokens.push(tok);
                s = &s[2..];
                continue;
            }
        }

        let tok = match s.as_bytes()[0] {
            b'+' => Token::Plus,
            b'-' => Token::Minus,
            b'*' => Token::Star,
            b'/' => Token::Slash,
            b'<' => Token::Lt,
            b'>' => Token::Gt,
            b'(' => Token::LParen,
            b')' => Token::RParen,
            _ => {
                return Err(miette::Error::msg(format!(
                    "unexpected character: '{}'",
                    &s[..1]
                )));
            }
        };
        tokens.push(tok);
        s = &s[1..];
    }
    Ok(tokens)
}

// ── Parser (recursive descent on token slice) ──

type Res<'a> = miette::Result<(&'a [Token], u32)>;

fn parse_or<'a>(t: &'a [Token], cpu: &dyn AbstractCpu) -> Res<'a> {
    let (mut t, mut acc) = parse_and(t, cpu)?;
    loop {
        match t {
            [Token::Or, rest @ ..] => {
                let (rest, v) = parse_and(rest, cpu)?;
                acc = u32::from(acc != 0 || v != 0);
                t = rest;
            }
            _ => return Ok((t, acc)),
        }
    }
}

fn parse_and<'a>(t: &'a [Token], cpu: &dyn AbstractCpu) -> Res<'a> {
    let (mut t, mut acc) = parse_eq(t, cpu)?;
    loop {
        match t {
            [Token::And, rest @ ..] => {
                let (rest, v) = parse_eq(rest, cpu)?;
                acc = u32::from(acc != 0 && v != 0);
                t = rest;
            }
            _ => return Ok((t, acc)),
        }
    }
}

fn parse_eq<'a>(t: &'a [Token], cpu: &dyn AbstractCpu) -> Res<'a> {
    let (mut t, mut acc) = parse_cmp(t, cpu)?;
    loop {
        match t {
            [Token::Eq, rest @ ..] => {
                let (rest, v) = parse_cmp(rest, cpu)?;
                acc = u32::from(acc == v);
                t = rest;
            }
            [Token::Ne, rest @ ..] => {
                let (rest, v) = parse_cmp(rest, cpu)?;
                acc = u32::from(acc != v);
                t = rest;
            }
            _ => return Ok((t, acc)),
        }
    }
}

fn parse_cmp<'a>(t: &'a [Token], cpu: &dyn AbstractCpu) -> Res<'a> {
    let (mut t, mut acc) = parse_add(t, cpu)?;
    loop {
        let cmp: fn(i32, i32) -> bool = match t.first() {
            Some(Token::Lt) => |a, b| a < b,
            Some(Token::Le) => |a, b| a <= b,
            Some(Token::Gt) => |a, b| a > b,
            Some(Token::Ge) => |a, b| a >= b,
            _ => return Ok((t, acc)),
        };
        let (rest, v) = parse_add(&t[1..], cpu)?;
        acc = u32::from(cmp(acc as i32, v as i32));
        t = rest;
    }
}

fn parse_add<'a>(t: &'a [Token], cpu: &dyn AbstractCpu) -> Res<'a> {
    let (mut t, mut acc) = parse_mul(t, cpu)?;
    loop {
        match t {
            [Token::Plus, rest @ ..] => {
                let (rest, v) = parse_mul(rest, cpu)?;
                acc = acc.wrapping_add(v);
                t = rest;
            }
            [Token::Minus, rest @ ..] => {
                let (rest, v) = parse_mul(rest, cpu)?;
                acc = acc.wrapping_sub(v);
                t = rest;
            }
            _ => return Ok((t, acc)),
        }
    }
}

fn parse_mul<'a>(t: &'a [Token], cpu: &dyn AbstractCpu) -> Res<'a> {
    let (mut t, mut acc) = parse_unary(t, cpu)?;
    loop {
        match t {
            [Token::Star, rest @ ..] => {
                let (rest, v) = parse_unary(rest, cpu)?;
                acc = acc.wrapping_mul(v);
                t = rest;
            }
            [Token::Slash, rest @ ..] => {
                let (rest, v) = parse_unary(rest, cpu)?;
                if v == 0 {
                    return Err(miette::Error::msg("division by zero"));
                }
                acc = (acc as i32).wrapping_div(v as i32) as u32;
                t = rest;
            }
            _ => return Ok((t, acc)),
        }
    }
}

fn parse_unary<'a>(t: &'a [Token], cpu: &dyn AbstractCpu) -> Res<'a> {
    match t {
        [Token::Minus, rest @ ..] => {
            let (rest, v) = parse_unary(rest, cpu)?;
            Ok((rest, 0u32.wrapping_sub(v)))
        }
        [Token::Star, rest @ ..] => {
            let (rest, addr) = parse_unary(rest, cpu)?;
            let v = cpu.mem_load_u32(addr)?;
            Ok((rest, v))
        }
        _ => parse_primary(t, cpu),
    }
}

fn parse_primary<'a>(t: &'a [Token], cpu: &dyn AbstractCpu) -> Res<'a> {
    match t {
        [Token::Num(v), rest @ ..] => Ok((rest, *v)),
        [Token::Reg(name), rest @ ..] => {
            let v = cpu.value(name)?;
            Ok((rest, v))
        }
        [Token::LParen, rest @ ..] => {
            let (rest, v) = parse_or(rest, cpu)?;
            match rest {
                [Token::RParen, rest @ ..] => Ok((rest, v)),
                _ => Err(miette::Error::msg(
                    "unexpected trailing tokens in parentheses",
                )),
            }
        }
        _ => Err(miette::Error::msg("unexpected token in expression")),
    }
}

// ── Public API ──

pub fn eval_tokens(tokens: &[Token], cpu: &dyn AbstractCpu) -> miette::Result<u32> {
    let (rest, val) = parse_or(tokens, cpu)?;
    if !rest.is_empty() {
        return Err(miette::Error::msg("unexpected trailing tokens"));
    }
    Ok(val)
}

pub fn eval(expr: &str, cpu: &dyn AbstractCpu) -> miette::Result<u32> {
    let tokens = tokenize(expr)?;
    eval_tokens(&tokens, cpu)
}

#[cfg(test)]
mod tests {
    use super::*;

    struct DummyCpu;

    impl AbstractCpu for DummyCpu {
        fn pc(&self) -> u32 {
            0x80000000
        }
        fn gpr(&self, index: usize) -> miette::Result<u32> {
            Ok((index as u32) * 10)
        }
        fn set_gpr(&mut self, _: usize, _: u32) -> miette::Result<()> {
            Ok(())
        }
        fn mstatus(&self) -> u32 {
            0
        }
        fn set_mstatus(&mut self, _: u32) {}
        fn mtvec(&self) -> u32 {
            0
        }
        fn set_mtvec(&mut self, _: u32) {}
        fn mepc(&self) -> u32 {
            0
        }
        fn set_mepc(&mut self, _: u32) {}
        fn mcause(&self) -> u32 {
            0
        }
        fn set_mcause(&mut self, _: u32) {}
        fn mtval(&self) -> u32 {
            0
        }
        fn set_mtval(&mut self, _: u32) {}
        fn mvendorid(&self) -> u32 {
            0
        }
        fn set_mvendorid(&mut self, _: u32) {}
        fn marchid(&self) -> u32 {
            0
        }
        fn set_marchid(&mut self, _: u32) {}
        fn mem_load_u8(&self, _: u32) -> miette::Result<u8> {
            Ok(0)
        }
        fn mem_load_u16(&self, _: u32) -> miette::Result<u16> {
            Ok(0)
        }
        fn mem_load_u32(&self, _: u32) -> miette::Result<u32> {
            Ok(0x12345678)
        }
        fn mem_store_u8(&mut self, _: u32, _: u8) -> miette::Result<()> {
            Ok(())
        }
        fn mem_store_u16(&mut self, _: u32, _: u16) -> miette::Result<()> {
            Ok(())
        }
        fn mem_store_u32(&mut self, _: u32, _: u32) -> miette::Result<()> {
            Ok(())
        }
        fn reset(&mut self) {}
        fn step(&mut self) -> miette::Result<()> {
            Ok(())
        }
    }

    #[test]
    fn test_arithmetic() {
        let cpu = DummyCpu;
        assert_eq!(eval("1 + 2", &cpu).unwrap(), 3);
        assert_eq!(eval("10 - 3", &cpu).unwrap(), 7);
        assert_eq!(eval("3 * 4", &cpu).unwrap(), 12);
        assert_eq!(eval("15 / 3", &cpu).unwrap(), 5);
        assert_eq!(eval("2 + 3 * 4", &cpu).unwrap(), 14);
        assert_eq!(eval("(2 + 3) * 4", &cpu).unwrap(), 20);
    }

    #[test]
    fn test_unary() {
        let cpu = DummyCpu;
        assert_eq!(eval("-1", &cpu).unwrap(), (-1i32) as u32);
        assert_eq!(eval("--1", &cpu).unwrap(), 1);
        assert_eq!(eval("-(3 + 4)", &cpu).unwrap(), (-7i32) as u32);
    }

    #[test]
    fn test_comparison() {
        let cpu = DummyCpu;
        assert_eq!(eval("1 == 1", &cpu).unwrap(), 1);
        assert_eq!(eval("1 == 2", &cpu).unwrap(), 0);
        assert_eq!(eval("1 != 2", &cpu).unwrap(), 1);
        assert_eq!(eval("1 < 2", &cpu).unwrap(), 1);
        assert_eq!(eval("2 <= 2", &cpu).unwrap(), 1);
        assert_eq!(eval("3 > 2", &cpu).unwrap(), 1);
        assert_eq!(eval("2 >= 3", &cpu).unwrap(), 0);
    }

    #[test]
    fn test_logic() {
        let cpu = DummyCpu;
        assert_eq!(eval("1 && 1", &cpu).unwrap(), 1);
        assert_eq!(eval("1 && 0", &cpu).unwrap(), 0);
        assert_eq!(eval("0 || 1", &cpu).unwrap(), 1);
        assert_eq!(eval("0 || 0", &cpu).unwrap(), 0);
    }

    #[test]
    fn test_hex() {
        let cpu = DummyCpu;
        assert_eq!(eval("0xff", &cpu).unwrap(), 255);
        assert_eq!(eval("0x10 + 1", &cpu).unwrap(), 17);
    }

    #[test]
    fn test_register() {
        let cpu = DummyCpu;
        assert_eq!(eval("$pc", &cpu).unwrap(), 0x80000000);
        assert_eq!(eval("$a0", &cpu).unwrap(), 100);
        assert_eq!(eval("$x0", &cpu).unwrap(), 0);
    }

    #[test]
    fn test_deref() {
        let cpu = DummyCpu;
        assert_eq!(eval("*0x80000000", &cpu).unwrap(), 0x12345678);
        assert_eq!(eval("*(0x80000000)", &cpu).unwrap(), 0x12345678);
    }

    #[test]
    fn test_div_by_zero() {
        let cpu = DummyCpu;
        assert!(eval("1 / 0", &cpu).is_err());
    }
}
