mod dpi;

use clap::Parser;
use log::info;
use npc::args::MonitorArgs;
use npc::libcpu::VerilatorCpu;
use npc::libcpu::verilator::globals;
use npc::libsdb::{ScoreBoard, Sdb};

fn main() -> miette::Result<()> {
    let args = MonitorArgs::parse();
    env_logger::init();

    let bin_path = args.image.clone();
    info!("bin_path: {:?}", bin_path);
    let elf_path = std::path::PathBuf::from(bin_path.to_string_lossy().replace(".bin", ".elf"));
    info!("elf_path: {:?}", elf_path);
    let flash_data = std::fs::read(&elf_path)
        .map_err(|e| miette::Error::msg(format!("failed to read image {:?}: {e}", args.image)))?;

    globals::init(&flash_data);
    let mut dut = VerilatorCpu::new();
    let mut scoreboard = ScoreBoard::new(&flash_data);
    let mut sdb = Sdb::new(&mut scoreboard, args.batch);
    sdb.mainloop(&mut dut)
}
