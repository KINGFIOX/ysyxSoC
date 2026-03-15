use std::collections::VecDeque;
use std::sync::atomic::{AtomicI32, AtomicU64, Ordering};
use std::time::{Duration, Instant};

use log::{info, warn};

const FORK_INTERVAL: Duration = Duration::from_secs(3);
const SLOT_SIZE: usize = 2;

#[repr(C)]
struct SharedInfo {
    /// POSIX semaphore for parent-to-child wakeup (pshared, init=0)
    wakeup: libc::sem_t,
    end_cycles: AtomicU64,
    oldest_pid: AtomicI32,
}

struct ForkShareMemory {
    info: *mut SharedInfo,
}

unsafe impl Send for ForkShareMemory {}

impl ForkShareMemory {
    fn new() -> Self {
        let size = std::mem::size_of::<SharedInfo>();
        let ptr = unsafe {
            libc::mmap(
                std::ptr::null_mut(),
                size,
                libc::PROT_READ | libc::PROT_WRITE,
                libc::MAP_SHARED | libc::MAP_ANONYMOUS,
                -1,
                0,
            )
        };
        assert!(ptr != libc::MAP_FAILED, "mmap failed for shared memory");

        let info = ptr as *mut SharedInfo;
        unsafe {
            let ret = libc::sem_init(&raw mut (*info).wakeup, 1, 0);
            assert!(ret == 0, "sem_init failed");
            (*info).end_cycles = AtomicU64::new(0);
            (*info).oldest_pid = AtomicI32::new(0);
        }

        Self { info }
    }

    fn info(&self) -> &SharedInfo {
        unsafe { &*self.info }
    }

    /// Child blocks here until parent calls sem_post.
    fn shwait(&self) {
        unsafe {
            libc::sem_wait(&raw mut (*self.info).wakeup);
        }
    }

    /// Parent posts to wake the blocked child.
    fn post(&self) {
        unsafe {
            libc::sem_post(&raw mut (*self.info).wakeup);
        }
    }
}

impl Drop for ForkShareMemory {
    fn drop(&mut self) {
        unsafe {
            libc::sem_destroy(&raw mut (*self.info).wakeup);
            libc::munmap(
                self.info as *mut libc::c_void,
                std::mem::size_of::<SharedInfo>(),
            );
        }
    }
}

#[derive(Debug, PartialEq)]
pub enum ForkResult {
    Ok,
    Child { end_cycles: u64 },
}

pub struct LightSSS {
    shm: ForkShareMemory,
    pid_slots: VecDeque<i32>,
    is_child_process: bool,
    /// timestamp of the last fork
    last_fork: Instant,
}

impl LightSSS {
    pub fn new() -> Self {
        Self {
            shm: ForkShareMemory::new(),
            pid_slots: VecDeque::new(),
            is_child_process: false,
            last_fork: Instant::now(),
        }
    }

    pub fn should_fork(&self) -> bool {
        !self.is_child_process && self.last_fork.elapsed() >= FORK_INTERVAL
    }

    pub fn is_child(&self) -> bool {
        self.is_child_process
    }

    pub fn do_fork(&mut self) -> ForkResult {
        if self.pid_slots.len() >= SLOT_SIZE {
            let oldest = self.pid_slots.pop_back().unwrap();
            unsafe { libc::kill(oldest, libc::SIGKILL) };
            unsafe { libc::waitpid(oldest, std::ptr::null_mut(), 0) };
        }

        let pid = unsafe { libc::fork() };
        assert!(pid >= 0);
        if pid > 0 {
            // parent
            self.pid_slots.push_front(pid);
            self.last_fork = Instant::now();
            info!("[lightsss] forked checkpoint pid={pid}, slots={}", self.pid_slots.len());
            ForkResult::Ok
        } else {
            // child: P operation — blocks until parent V's
            self.is_child_process = true;
            self.shm.shwait();

            let my_pid = unsafe { libc::getpid() };
            assert!(self.shm.info().oldest_pid.load(Ordering::Acquire) == my_pid);
            let end_cycles = self.shm.info().end_cycles.load(Ordering::Acquire);
            info!("[lightsss] child pid={my_pid} woke up, will dump wave until cycle {end_cycles}");
            ForkResult::Child { end_cycles }
        }
    }

    /// called by `lightsss_on_error`, which means parent crashed
    /// only parent could enter in this
    pub fn wakeup_child(&mut self, cycles: u64) {
        if self.pid_slots.is_empty() {
            warn!("[lightsss] no checkpoint to wake up");
            return;
        }

        self.shm.info().end_cycles.store(cycles, Ordering::Release);
        let oldest = *self.pid_slots.back().unwrap();
        self.shm.info().oldest_pid.store(oldest, Ordering::Release);

        for &pid in &self.pid_slots {
            if pid != oldest {
                unsafe { libc::kill(pid, libc::SIGKILL) };
                unsafe { libc::waitpid(pid, std::ptr::null_mut(), 0) };
            }
        }

        // V operation — wakes the oldest child blocked on sem_wait
        self.shm.post();

        info!("[lightsss] waking child pid={oldest}, waiting...");
        unsafe { libc::waitpid(oldest, std::ptr::null_mut(), 0) };
        info!("[lightsss] child finished");

        self.pid_slots.clear();
    }

    pub fn do_clear(&mut self) {
        while let Some(pid) = self.pid_slots.pop_back() {
            unsafe { libc::kill(pid, libc::SIGKILL) };
            unsafe { libc::waitpid(pid, std::ptr::null_mut(), 0) };
        }
    }
}

impl Drop for LightSSS {
    fn drop(&mut self) {
        if !self.is_child_process {
            self.do_clear();
        }
    }
}
