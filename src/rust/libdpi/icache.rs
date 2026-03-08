#[allow(unused_imports)]
use log::info;

/// 64B cacheline
#[derive(Default, Clone)]
struct CacheLine {
    valid: bool,
    tag: u32,
    data: [u32; 16], // 4B * 16 = 64B
    lru: u8,
}

/// 4-way set associative
#[derive(Default, Clone)]
struct CacheSet {
    lines: [CacheLine; 4],
}

/// 16KB/32KB/64KB cache size
pub struct ICache {
    sets: Vec<CacheSet>,
    victim_line : Option<usize>,
    counter: u32,
}

impl ICache {
    pub fn new() -> Self {
        let sets = vec![CacheSet::default(); 64];
        Self { sets, victim_line: None, counter: 0 }
    }

    /// Parse address into tag, index, and offset
    fn parse_addr(addr: u32) -> (u32, usize, usize) {
        let word_addr = addr >> 2;
        let offset = (word_addr & 0xF) as usize;
        let idx = ((word_addr >> 4) & 0x3F) as usize;
        let tag = (word_addr >> 10) as u32;
        (tag, idx, offset)
    }

    pub fn lookup(&mut self, addr: u32) -> Option<u32> {
        let (tag, idx, offset) = Self::parse_addr(addr);
        let set = &mut self.sets[idx];

        // info!("lookup: addr={:x} tag={:x} idx={} offset={}", addr, tag, idx, offset);

        let found = set.lines.iter().position(|l| l.valid && l.tag == tag);

        set.lines
            .iter_mut()
            .filter(|l| l.valid)
            .for_each(|l| l.lru = l.lru.saturating_add(1));

        if let Some(hit) = found {
            set.lines[hit].lru = 0;
            return Some(set.lines[hit].data[offset]);
        }
        
        // only icache miss case would be here

        let victim = set
            .lines
            .iter()
            .position(|l| !l.valid)
            .unwrap_or_else(|| {
                set.lines
                    .iter()
                    .enumerate()
                    .max_by_key(|(_, l)| l.lru)
                    .unwrap()
                    .0
            });

        set.lines[victim].tag = tag;
        set.lines[victim].valid = true;
        set.lines[victim].lru = 0;
        self.victim_line = Some(victim);
        self.counter = 16;

        None
    }

    pub fn refill(&mut self, addr: i32, data: i32) {
        let (tag, idx, offset) = Self::parse_addr(addr as u32);
        // info!("refill: addr={:08x}, data={:08x}, tag={:08x}, idx={}, offset={}", addr, data, tag, idx, offset);
        let victim = self.victim_line.expect("refill: no victim line set");
        let line = &mut self.sets[idx].lines[victim];

        assert!(
            line.valid && line.tag == tag,
            "refill: tag mismatch addr={:x} tag={:x} idx={} offset={}",
            addr, tag, idx, offset
        );

        // log::trace!("refill: write addr={:x} offset={} data={:x}", addr, offset, data);
        line.data[offset] = data as u32;
        self.counter -= 1;
        if self.counter == 0 {
            self.victim_line = None;
        }
    }
}
