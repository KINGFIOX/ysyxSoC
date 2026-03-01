pub trait AbstractDevice {
    fn read(&self, offset: u32) -> miette::Result<u8>;
    fn write(&mut self, offset: u32, value: u8) -> miette::Result<()>;
    fn size(&self) -> u32;
}

pub struct ReadOnlyDevice {
    data: Vec<u8>,
}

impl ReadOnlyDevice {
    pub fn new(img: &[u8], size: u32) -> Self {
        let mut data = vec![0; size as usize];
        data.copy_from_slice(img);
        Self {
            data,
        }
    }
}

impl AbstractDevice for ReadOnlyDevice {
    fn read(&self, offset: u32) -> miette::Result<u8> {
        if offset >= self.data.len() as u32 {
            return Err(miette::Error::msg("read out of range"));
        }
        Ok(self.data[offset as usize])
    }
    fn write(&mut self, _offset: u32, _value: u8) -> miette::Result<()> {
        Err(miette::Error::msg("write to read-only device"))
    }
    fn size(&self) -> u32 {
        self.data.len() as u32
    }
}

pub struct ReadWriteDevice {
    data: Vec<u8>,
}

impl ReadWriteDevice {
    pub fn new(size: u32) -> Self {
        Self {
            data: vec![0; size as usize],
        }
    }
}

impl AbstractDevice for ReadWriteDevice {
    fn read(&self, offset: u32) -> miette::Result<u8> {
        if offset >= self.data.len() as u32 {
            return Err(miette::Error::msg("read out of range"));
        }
        Ok(self.data[offset as usize])
    }
    fn write(&mut self, offset: u32, value: u8) -> miette::Result<()> {
        if offset >= self.data.len() as u32 {
            return Err(miette::Error::msg("write out of range"));
        }
        self.data[offset as usize] = value;
        Ok(())
    }

    fn size(&self) -> u32 {
        self.data.len() as u32
    }
}