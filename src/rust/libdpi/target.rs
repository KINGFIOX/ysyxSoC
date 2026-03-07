use std::sync::{Arc, Mutex, OnceLock};

pub struct DpiTarget<T>(OnceLock<Arc<Mutex<T>>>);

impl<T> DpiTarget<T> {
    pub const fn new() -> Self {
        Self(OnceLock::new())
    }

    pub fn init(&self, target: Arc<Mutex<T>>) {
        if self.0.set(target).is_err() {
            panic!("DpiTarget is already initialized");
        }
    }

    pub fn with<R>(&self, f: impl FnOnce(&T) -> R) -> R {
        let target = self.0.get().expect("DpiTarget is not initialized");
        let target = target.lock().unwrap();
        f(&target)
    }

    pub fn with_mut<R>(&self, f: impl FnOnce(&mut T) -> R) -> R {
        let target = self.0.get().expect("DpiTarget is not initialized");
        let mut target = target.lock().unwrap();
        f(&mut target)
    }
}
