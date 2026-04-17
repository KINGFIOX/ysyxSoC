#include <cstdlib>
#include <fstream>
#include <iterator>
#include <memory>
#include <string>
#include <vector>

#include "absl/flags/flag.h"
#include "absl/flags/parse.h"
#include "absl/log/globals.h"
#include "absl/log/initialize.h"
#include "absl/log/log.h"
#ifdef NPC_FTRACE
#include "absl/strings/str_replace.h"
#endif
#include "common/args.h"
#include "cpu/verilator_cpu.h"
#include "dpi/sync_disk.h"
#include "sdb/scoreboard.h"
#include "sdb/sdb.h"
#ifdef NPC_FTRACE
#include "tracer/ftrace.h"
#endif

ABSL_FLAG(bool, batch, false, "Run in batch mode (no interactive debugger)");
ABSL_FLAG(bool, nvboard, false, "Enable NVBoard visualization");
ABSL_FLAG(bool, wave, false, "Enable waveform dumping");
ABSL_FLAG(uint64_t, wave_tail, 100000,
          "Keep last N cycles in waveform (0 = unlimited)");
ABSL_FLAG(std::string, image, "", "Path to binary image file");
ABSL_FLAG(std::string, fsimg, "", "Disk image file for sync MMIO disk");
ABSL_FLAG(std::string, log, "", "Path to log file (unused currently)");

int main(int argc, char* argv[]) {
  absl::ParseCommandLine(argc, argv);
  absl::InitializeLog();
  absl::SetStderrThreshold(absl::LogSeverityAtLeast::kInfo);

  std::string bin_path = absl::GetFlag(FLAGS_image);
  if (bin_path.empty()) {
    LOG(FATAL) << "No image specified. Use --image=<path>";
    return 1;
  }
  LOG(INFO) << "bin_path: " << bin_path;

#ifdef NPC_FTRACE
  // Try <image>.elf first, then fall back to <image without ".bin">
  std::string elf_path = absl::StrReplaceAll(bin_path, {{".bin", ".elf"}});
  if (!std::ifstream(elf_path).good()) {
    std::string alt = bin_path;
    const std::string bin_ext = ".bin";
    if (alt.size() >= bin_ext.size() &&
        alt.compare(alt.size() - bin_ext.size(), bin_ext.size(), bin_ext) == 0) {
      alt = alt.substr(0, alt.size() - bin_ext.size());
    }
    if (std::ifstream(alt).good()) elf_path = alt;
  }
  LOG(INFO) << "elf_path: " << elf_path;
  auto ftrace = std::make_unique<npc::FuncTracer>(elf_path);
#endif

  // Read binary image
  std::ifstream ifs(bin_path, std::ios::binary);
  if (!ifs) {
    LOG(FATAL) << "Failed to read image file: " << bin_path;
    return 1;
  }
  std::vector<uint8_t> flash_data(std::istreambuf_iterator<char>(ifs), {});
  ifs.close();

  npc::VerilatorCpu dut(flash_data, absl::GetFlag(FLAGS_nvboard));
  npc::g_memory->load_sdram(flash_data);

  std::string fsimg_path = absl::GetFlag(FLAGS_fsimg);
  std::unique_ptr<npc::SyncDisk> sync_disk;
  if (!fsimg_path.empty()) {
    sync_disk = std::make_unique<npc::SyncDisk>(fsimg_path);
    npc::g_sync_disk = sync_disk.get();
  }

  if (absl::GetFlag(FLAGS_wave)) {
    dut.enable_wave(absl::GetFlag(FLAGS_wave_tail));
  }
#ifdef NPC_FTRACE
  npc::ScoreBoard scrbrd(flash_data, std::move(ftrace));
#else
  npc::ScoreBoard scrbrd(flash_data);
#endif
  npc::Sdb sdb;

  auto status = sdb.mainloop(scrbrd, dut, absl::GetFlag(FLAGS_batch));
  if (!status.ok()) {
    scrbrd.dump_traces(dut);
    LOG(ERROR) << status;
    return 1;
  }

  uint32_t branch_cnt = dut.perf_branch_cnt();
  uint32_t mispredict_cnt = dut.perf_branch_mispredict_cnt();
  float hit_rate = branch_cnt > 0
                       ? static_cast<float>(branch_cnt - mispredict_cnt) /
                             static_cast<float>(branch_cnt)
                       : 0.0F;
  LOG(INFO) << "commit: " << dut.perf_commit_cnt();
  LOG(INFO) << "branch: " << branch_cnt;
  LOG(INFO) << "mispredict: " << mispredict_cnt;
  LOG(INFO) << "hit rate: " << hit_rate;
  LOG(INFO) << "flush: " << dut.perf_flush_cnt();

  return 0;
}
