use clap::Parser;
use std::path::PathBuf;

#[derive(Parser, Debug)]
#[command(about = "NPC - RISC-V CPU Simulator")]
pub struct MonitorArgs {
    #[arg(short, long)]
    pub batch: bool,

    #[arg(short, long)]
    pub log: Option<PathBuf>,

    pub image: PathBuf,
}
