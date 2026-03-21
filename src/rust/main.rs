use clap::Parser;
use log::info;

use npc::common::args::MonitorArgs;
use npc::libcpu::verilator::cpu::VerilatorCpu;
use npc::libsdb::scoreboard::ScoreBoard;
use npc::libsdb::sdb::Sdb;
use npc::tracer::ftrace::FuncTracer;

fn main() -> miette::Result<()> {
    let args = MonitorArgs::parse();
    env_logger::init(); // TODO: args.log 暂时还没用到

    let bin_path = args.image.clone();
    info!("bin_path: {:?}", bin_path);

    let elf_path = std::path::PathBuf::from(bin_path.to_string_lossy().replace(".bin", ".elf"));
    info!("elf_path: {:?}", elf_path);
    let ftrace = Box::new(FuncTracer::new(&elf_path));

    let flash_data = std::fs::read(&bin_path)
        .map_err(|e| miette::Error::msg(format!("failed to read image file: {}", e)))?;
    let mut dut = VerilatorCpu::new(&flash_data, args.nvboard);
    let mut scoreboard = ScoreBoard::new(&flash_data, ftrace);
    let mut sdb = Sdb::new(&mut scoreboard, true);
    if let Err(e) = sdb.mainloop(&mut dut, args.batch) {
        sdb.lightsss_on_error(&dut);
        scoreboard.dump_traces(&dut);
        Err(e)
    } else {
        let branch_cnt = dut.perf_branch_cnt();
        let mispredict_cnt = dut.perf_branch_mispredict_cnt();
        let hit_rate: f32 = (branch_cnt - mispredict_cnt) as f32 / branch_cnt as f32;
        let flush_cnt = dut. perf_flush_cnt();
        info!("commit: {0}", dut.perf_commit_cnt());
        info!("branch: {0}", branch_cnt);
        info!("mispredict: {0}", mispredict_cnt);
        info!("hit rate: {0}", hit_rate);
        info!("flush: {0}", flush_cnt);
        Ok(())
    }
}
