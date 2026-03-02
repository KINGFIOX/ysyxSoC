use clap::Parser;
use std::path::PathBuf;

#[derive(Parser, Debug)]
#[command(about = "NPC - RISC-V CPU Simulator")]
pub struct MonitorArgs {
    #[arg(short, long, help = "run with batch mode")]
    pub batch: bool,

    #[arg(long, help = "generate VCD waveform file")]
    pub wave: bool,

    #[arg(long, help = "use NVBoard simulation board")]
    pub nvboard: bool,

    #[arg(short, long, help = "write log to specified file")]
    pub log: Option<PathBuf>, /// TODO: temporarily not used

    #[arg(short, long, help = "RISC-V image file path (.bin)")]
    pub image: PathBuf,
}
