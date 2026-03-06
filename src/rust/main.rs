mod dpi;

use clap::Parser;
use log::info;
use npc::args::MonitorArgs;
use npc::libcpu::VerilatorCpu;
use npc::libcpu::verilator::globals;
use npc::libsdb::{ScoreBoard, Sdb};
use npc::tracer::FuncTracer;

fn main() -> miette::Result<()> {
    let args = MonitorArgs::parse();
    env_logger::init(); // TODO: args.log 暂时还没用到

    let bin_path = args.image.clone();
    info!("bin_path: {:?}", bin_path);

    let elf_path = std::path::PathBuf::from(bin_path.to_string_lossy().replace(".bin", ".elf"));
    info!("elf_path: {:?}", elf_path);
    let ftrace = Box::new(FuncTracer::new(&elf_path));

    let flash_data = std::fs::read(&bin_path).map_err(|e| miette::Error::msg(format!("failed to read image file: {}", e)))?;
    globals::init(&flash_data);
    let mut dut = VerilatorCpu::new(true, args.nvboard); // default wave is true
    let mut scoreboard = ScoreBoard::new(&flash_data, ftrace);
    let mut sdb = Sdb::new(&mut scoreboard);
    let result = sdb.mainloop(&mut dut, args.batch); // ignore the result
    scoreboard.dump_traces(&dut);
    result
}
