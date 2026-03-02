use std::fmt;

pub struct RingBuf<T> {
    buf: Box<[Option<T>]>,
    head: usize,
    len: usize,
}

impl<T> RingBuf<T> {
    pub fn new(capacity: usize) -> Self {
        assert!(capacity > 0);
        let buf = (0..capacity).map(|_| None).collect::<Vec<_>>().into_boxed_slice();
        Self { buf, head: 0, len: 0 }
    }

    pub fn push(&mut self, entry: T) {
        let cap = self.buf.len();
        self.buf[self.head] = Some(entry);
        self.head = (self.head + 1) % cap;
        if self.len < cap {
            self.len += 1;
        }
    }
}

impl<T: fmt::Display> RingBuf<T> {
    pub fn dump(&self) -> String {
        let cap = self.buf.len();
        let start = if self.len < cap { 0 } else { self.head };
        let mut out = String::new();
        for i in 0..self.len {
            let idx = (start + i) % cap;
            if let Some(entry) = &self.buf[idx] {
                out.push_str(&format!("{entry}\n"));
            }
        }
        out
    }
}
