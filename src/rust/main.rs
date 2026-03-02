mod dpi;

use clap::Parser;
use log::info;
use npc::args::MonitorArgs;
use npc::libcpu::VerilatorCpu;
use npc::libcpu::verilator::globals;
use npc::libsdb::{ScoreBoard, Sdb};
use npc::tracer::FuncTracer;

fn main() {
    let args = MonitorArgs::parse();
    env_logger::init(); // TODO: args.log 暂时还没用到

    let bin_path = args.image.clone();
    info!("bin_path: {:?}", bin_path);

    let elf_path = std::path::PathBuf::from(bin_path.to_string_lossy().replace(".bin", ".elf"));
    info!("elf_path: {:?}", elf_path);
    let ftrace = Box::new(FuncTracer::new(&elf_path));

    let flash_data = std::fs::read(&bin_path).unwrap();
    globals::init(&flash_data);
    let mut dut = VerilatorCpu::new();
    let mut scoreboard = ScoreBoard::new(&flash_data, ftrace);
    let mut sdb = Sdb::new(&mut scoreboard);
    sdb.mainloop(&mut dut, args.batch).unwrap();
    scoreboard.dump_traces(&dut);
}
