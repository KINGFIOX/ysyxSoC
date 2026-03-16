use std::collections::VecDeque;
use std::time::{Duration, Instant};

use log::{info, warn};

const FORK_INTERVAL: Duration = Duration::from_secs(3);
const SLOT_SIZE: usize = 2;

#[derive(Debug, PartialEq)]
pub enum ForkResult {
    Ok,
    Child { end_cycles: u64 },
}

/// Every process has its own `LightSSS` instance after fork.
/// - `pid_slots`: only used by parent, stores `(child_pid, pipe_write_fd)`
/// - `last_fork`: timestamp of last fork (parent) or of creation (child)
pub struct LightSSS {
    /// (rfd, wfd)
    pipe: (i32, i32),
    pid_slots: VecDeque<i32>,
    is_child_process: bool,
    last_fork: Instant,
}

impl LightSSS {
    pub fn new() -> Self {
        let mut fds = [0i32; 2];
        let ret = unsafe { libc::pipe(fds.as_mut_ptr()) };
        assert!(ret == 0, "pipe() failed");
        Self {
            pipe: (fds[0], fds[1]),
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
        // queue full, pop one
        if self.pid_slots.len() >= SLOT_SIZE {
            let oldest_pid = self.pid_slots.pop_back().unwrap();
            unsafe { libc::kill(oldest_pid, libc::SIGKILL) };
            unsafe { libc::waitpid(oldest_pid, std::ptr::null_mut(), 0) };
        }

        let pid = unsafe { libc::fork() };
        assert!(pid >= 0, "fork() failed");

        if pid > 0 {
            self.pid_slots.push_front(pid);
            self.last_fork = Instant::now();
            info!(
                "[lightsss] forked checkpoint pid={pid}, slots={}",
                self.pid_slots.len()
            );
            ForkResult::Ok
        } else {
            self.pid_slots.clear(); // child, no use of pid_slots
            self.is_child_process = true;
            let mut buf = [0u8; size_of::<u64>()];
            let ret = unsafe {
                libc::read(
                    self.pipe.0,
                    buf.as_mut_ptr() as *mut libc::c_void,
                    size_of::<u64>(),
                )
            };
            assert!((ret as usize) == size_of::<u64>(), "read() failed");
            let end_cycles = u64::from_ne_bytes(buf);
            let my_pid = unsafe { libc::getpid() };
            info!("[lightsss] child pid={my_pid} woke up, will dump wave until cycle {end_cycles}");
            ForkResult::Child { end_cycles }
        }
    }

    pub fn wakeup_child(&mut self, cycles: u64) {
        if self.pid_slots.is_empty() {
            warn!("[lightsss] no checkpoint to wake up");
            return;
        }

        let oldest_pid = self.pid_slots.pop_back().unwrap();

        for &pid in &self.pid_slots {
            unsafe { libc::kill(pid, libc::SIGKILL) };
            unsafe { libc::waitpid(pid, std::ptr::null_mut(), 0) };
        }

        let buf: [u8; size_of::<u64>()] = cycles.to_ne_bytes();
        let n = unsafe {
            libc::write(
                self.pipe.1,
                buf.as_ptr() as *const libc::c_void,
                size_of::<u64>(),
            )
        };
        assert!((n as usize) == size_of::<u64>(), "pipe write failed");

        info!("[lightsss] waking child pid={oldest_pid}, waiting...");
        unsafe { libc::waitpid(oldest_pid, std::ptr::null_mut(), 0) };
        info!("[lightsss] child finished");

        self.pid_slots.clear();
    }

    pub fn do_clear(&mut self) {
        unsafe {
            libc::close(self.pipe.0);
        }
        unsafe {
            libc::close(self.pipe.1);
        }
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
