mod dpi;

use clap::Parser;
use npc::args::MonitorArgs;
use npc::libcpu::verilator::globals;
use npc::libcpu::VerilatorCpu;
use npc::libsdb::{ScoreBoard, Sdb};

fn main() -> miette::Result<()> {
    let args = MonitorArgs::parse();
    env_logger::init();

    let flash_data = std::fs::read(&args.image)
        .map_err(|e| miette::Error::msg(format!("failed to read image {:?}: {e}", args.image)))?;

    globals::init(&flash_data);
    let mut dut = VerilatorCpu::new();
    let mut scoreboard = ScoreBoard::new(&flash_data);
    let mut sdb = Sdb::new(&mut scoreboard, args.batch);
    sdb.mainloop(&mut dut)
}
