#include <chrono>
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
#include "perf/perf_counters.h"
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
ABSL_FLAG(std::string, itrace_log, "",
          "Append itrace (instruction trace) to this file in real time");
ABSL_FLAG(std::string, dtrace_log, "",
          "Append dtrace (MMIO trace) to this file in real time");
ABSL_FLAG(std::string, mtrace_log, "",
          "Append mtrace (memory trace) to this file in real time");
ABSL_FLAG(std::string, ftrace_log, "",
          "Append ftrace (function call trace) to this file in real time");
ABSL_FLAG(bool, ftrace_stdout, false,
          "Also mirror ftrace entries to stdout as they happen (useful when "
          "debugging a long-running boot with no other output channel).");
ABSL_FLAG(std::string, perf_json, "",
          "If non-empty, dump performance counters as JSON to this path");
ABSL_FLAG(std::string, perf_name, "",
          "Benchmark name to embed in the performance report/JSON");

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
  npc::g_golden_cpu = &scrbrd.golden();
  scrbrd.open_itrace_log(absl::GetFlag(FLAGS_itrace_log));
  scrbrd.open_dtrace_log(absl::GetFlag(FLAGS_dtrace_log));
  scrbrd.open_mtrace_log(absl::GetFlag(FLAGS_mtrace_log));
  scrbrd.open_ftrace_log(absl::GetFlag(FLAGS_ftrace_log));
#ifdef NPC_FTRACE
  scrbrd.set_ftrace_stdout(absl::GetFlag(FLAGS_ftrace_stdout));
#endif
  npc::Sdb sdb;

  auto wall_start = std::chrono::steady_clock::now();
  auto status = sdb.mainloop(scrbrd, dut, absl::GetFlag(FLAGS_batch));
  auto wall_end = std::chrono::steady_clock::now();
  uint64_t wall_us = static_cast<uint64_t>(
      std::chrono::duration_cast<std::chrono::microseconds>(wall_end -
                                                            wall_start)
          .count());
  if (!status.ok()) {
    scrbrd.dump_traces(dut);
    LOG(ERROR) << status;
    return 1;
  }

  // ---- Finalize perf report ----
  // All commit / branch / inst-mix counters are accumulated via DPI-C
  // events.  We only need to supply the host-side stats: benchmark name,
  // wall time, and the full simulation cycle count.
  auto& perf = npc::perf::PerfCounters::Instance();
  perf.set_benchmark_name(absl::GetFlag(FLAGS_perf_name));
  perf.set_cycles(dut.cycle_count());
  perf.set_elapsed_us(wall_us);

  perf.ReportStdout();
  std::string perf_json_path = absl::GetFlag(FLAGS_perf_json);
  if (!perf_json_path.empty()) {
    if (perf.DumpJson(perf_json_path)) {
      LOG(INFO) << "perf: wrote " << perf_json_path;
    }
  }

  return 0;
}
