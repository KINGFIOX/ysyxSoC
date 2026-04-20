#include "perf/perf_counters.h"

#include <cinttypes>
#include <cstdint>
#include <cstdio>
#include <fstream>
#include <string>

#include "absl/log/log.h"
#include "absl/strings/str_format.h"

namespace npc::perf {

PerfCounters& PerfCounters::Instance() {
  static PerfCounters kInstance;
  return kInstance;
}

namespace {

double SafeRatio(uint64_t num, uint64_t den) {
  return den == 0 ? 0.0 : static_cast<double>(num) / static_cast<double>(den);
}

std::string FmtRate(uint64_t num, uint64_t den) {
  return absl::StrFormat("%.4f (%" PRIu64 "/%" PRIu64 ")", SafeRatio(num, den),
                         num, den);
}

}  // namespace

void PerfCounters::ReportStdout() const {
  const uint64_t icache_access = Get(kIcacheAccess);
  const uint64_t icache_hit = Get(kIcacheHit);
  const uint64_t icache_miss = Get(kIcacheMiss);
  const uint64_t icache_miss_cycles = Get(kIcacheMissCycles);
  const uint64_t dcache_access = Get(kDcacheAccess);
  const uint64_t dcache_hit = Get(kDcacheHit);
  const uint64_t dcache_miss = Get(kDcacheMiss);
  const uint64_t dcache_miss_cycles = Get(kDcacheMissCycles);
  const uint64_t ifu_out = Get(kIfuOutValid);
  const uint64_t ifu_stall = Get(kIfuStall);

  const uint64_t commit = Get(kCommit);
  const uint64_t branch = Get(kBranch);
  const uint64_t branch_mispredict = Get(kBranchMispredict);
  const uint64_t flush = Get(kFlush);
  const uint64_t branch_hit =
      branch >= branch_mispredict ? branch - branch_mispredict : 0;
  const double ipc = SafeRatio(commit, cycles_);
  const double cpi = SafeRatio(cycles_, commit);

  std::printf("\n========================================"
              "========================================\n");
  std::printf("  NPC Performance Report  %s\n",
              bench_name_.empty() ? "" : bench_name_.c_str());
  std::printf("========================================"
              "========================================\n");

  std::printf("  benchmark        : %s\n",
              bench_name_.empty() ? "(unknown)" : bench_name_.c_str());
  std::printf("  wall time (us)   : %" PRIu64 "\n", elapsed_us_);
  std::printf("  sim cycles       : %" PRIu64 "\n", cycles_);
  std::printf("  committed insts  : %" PRIu64 "\n", commit);
  std::printf("  IPC              : %.4f\n", ipc);
  std::printf("  CPI              : %.4f\n", cpi);
  std::printf("\n");

  std::printf("-- Branch prediction --\n");
  std::printf("  total control-flow insts : %" PRIu64 "\n", branch);
  std::printf("  correctly predicted      : %" PRIu64 "\n", branch_hit);
  std::printf("  mispredicted             : %" PRIu64 "\n", branch_mispredict);
  std::printf("  hit rate                 : %s\n",
              FmtRate(branch_hit, branch).c_str());
  std::printf("  pipeline flushes         : %" PRIu64 "\n", flush);
  std::printf("\n");

  std::printf("-- ICache --\n");
  std::printf("  accesses         : %" PRIu64 "\n", icache_access);
  std::printf("  hits             : %" PRIu64 "\n", icache_hit);
  std::printf("  misses           : %" PRIu64 "\n", icache_miss);
  std::printf("  hit rate         : %s\n",
              FmtRate(icache_hit, icache_access).c_str());
  std::printf("  miss cycles      : %" PRIu64 "\n", icache_miss_cycles);
  std::printf("  avg miss penalty : %.2f cyc\n",
              SafeRatio(icache_miss_cycles, icache_miss));
  std::printf("  AMAT (cycles)    : %.4f\n",
              icache_access == 0
                  ? 0.0
                  : 1.0 + SafeRatio(icache_miss_cycles, icache_access));
  std::printf("\n");

  std::printf("-- DCache --\n");
  std::printf("  accesses         : %" PRIu64 "\n", dcache_access);
  std::printf("  hits             : %" PRIu64 "\n", dcache_hit);
  std::printf("  misses           : %" PRIu64 "\n", dcache_miss);
  std::printf("  hit rate         : %s\n",
              FmtRate(dcache_hit, dcache_access).c_str());
  std::printf("  miss cycles      : %" PRIu64 "\n", dcache_miss_cycles);
  std::printf("  avg miss penalty : %.2f cyc\n",
              SafeRatio(dcache_miss_cycles, dcache_miss));
  std::printf("  AMAT (cycles)    : %.4f\n",
              dcache_access == 0
                  ? 0.0
                  : 1.0 + SafeRatio(dcache_miss_cycles, dcache_access));
  std::printf("\n");

  const uint64_t fe_redir_br = Get(kFeRedirectBranch);
  const uint64_t fe_f1_miss  = Get(kFeF1TlbMiss);
  const uint64_t fe_f2_wait  = Get(kFeF2IcacheWait);
  const uint64_t fe_fp_wait  = Get(kFeFpRespWait);

  std::printf("-- Frontend (IFU) --\n");
  std::printf("  deliver cycles   : %" PRIu64 "\n", ifu_out);
  std::printf("  stall   cycles   : %" PRIu64 "\n", ifu_stall);
  std::printf("  stall ratio      : %s\n",
              FmtRate(ifu_stall, cycles_).c_str());
  std::printf("  FE redirect (branch)    : %" PRIu64 "\n", fe_redir_br);
  std::printf("  F1 TLB-miss cyc         : %" PRIu64 "\n", fe_f1_miss);
  std::printf("  F2 ICache-wait cyc      : %" PRIu64 "\n", fe_f2_wait);
  std::printf("  Fp response-wait cyc    : %" PRIu64 "\n", fe_fp_wait);
  std::printf("\n");

  std::printf("-- Instruction mix (committed) --\n");
  auto row = [&](const char* name, uint64_t v) {
    std::printf("  %-18s : %9" PRIu64 "  (%.2f%%)\n", name, v,
                SafeRatio(v, commit) * 100.0);
  };
  row("ALU (int/lui/auipc)", Get(kCommitAlu));
  row("MUL/DIV",             Get(kCommitMulDiv));
  row("LOAD",                Get(kCommitLoad));
  row("STORE",               Get(kCommitStore));
  row("BRANCH",              Get(kCommitCfBranch));
  row("JAL",                 Get(kCommitCfJal));
  row("JALR",                Get(kCommitCfJalr));
  row("CSR",                 Get(kCommitCsr));
  row("SYSTEM",              Get(kCommitSystem));
  row("FENCE",               Get(kCommitFence));
  std::printf("========================================"
              "========================================\n");
}

bool PerfCounters::DumpJson(const std::string& path) const {
  std::ofstream os(path);
  if (!os) {
    LOG(WARNING) << "Failed to open perf json output: " << path;
    return false;
  }

  const uint64_t icache_access = Get(kIcacheAccess);
  const uint64_t icache_hit = Get(kIcacheHit);
  const uint64_t icache_miss = Get(kIcacheMiss);
  const uint64_t icache_miss_cycles = Get(kIcacheMissCycles);
  const uint64_t dcache_access = Get(kDcacheAccess);
  const uint64_t dcache_hit = Get(kDcacheHit);
  const uint64_t dcache_miss = Get(kDcacheMiss);
  const uint64_t dcache_miss_cycles = Get(kDcacheMissCycles);
  const uint64_t ifu_out = Get(kIfuOutValid);
  const uint64_t ifu_stall = Get(kIfuStall);

  const uint64_t commit = Get(kCommit);
  const uint64_t branch = Get(kBranch);
  const uint64_t branch_mispredict = Get(kBranchMispredict);
  const uint64_t flush = Get(kFlush);
  const uint64_t branch_hit =
      branch >= branch_mispredict ? branch - branch_mispredict : 0;

  os << "{\n";
  os << "  \"benchmark\": \"" << bench_name_ << "\",\n";
  os << "  \"wall_us\": " << elapsed_us_ << ",\n";
  os << "  \"cycles\": " << cycles_ << ",\n";
  os << "  \"commit\": " << commit << ",\n";
  os << absl::StreamFormat("  \"ipc\": %.6f,\n", SafeRatio(commit, cycles_));
  os << absl::StreamFormat("  \"cpi\": %.6f,\n", SafeRatio(cycles_, commit));
  os << "  \"branch\": {\n";
  os << "    \"total\": " << branch << ",\n";
  os << "    \"hit\": " << branch_hit << ",\n";
  os << "    \"mispredict\": " << branch_mispredict << ",\n";
  os << absl::StreamFormat("    \"hit_rate\": %.6f,\n",
                           SafeRatio(branch_hit, branch));
  os << "    \"flushes\": " << flush << "\n";
  os << "  },\n";
  os << "  \"icache\": {\n";
  os << "    \"access\": " << icache_access << ",\n";
  os << "    \"hit\": " << icache_hit << ",\n";
  os << "    \"miss\": " << icache_miss << ",\n";
  os << absl::StreamFormat("    \"hit_rate\": %.6f,\n",
                           SafeRatio(icache_hit, icache_access));
  os << "    \"miss_cycles\": " << icache_miss_cycles << ",\n";
  os << absl::StreamFormat("    \"avg_miss_penalty\": %.4f,\n",
                           SafeRatio(icache_miss_cycles, icache_miss));
  os << absl::StreamFormat("    \"amat_cycles\": %.6f\n",
                           icache_access == 0
                               ? 0.0
                               : 1.0 + SafeRatio(icache_miss_cycles,
                                                 icache_access));
  os << "  },\n";
  os << "  \"dcache\": {\n";
  os << "    \"access\": " << dcache_access << ",\n";
  os << "    \"hit\": " << dcache_hit << ",\n";
  os << "    \"miss\": " << dcache_miss << ",\n";
  os << absl::StreamFormat("    \"hit_rate\": %.6f,\n",
                           SafeRatio(dcache_hit, dcache_access));
  os << "    \"miss_cycles\": " << dcache_miss_cycles << ",\n";
  os << absl::StreamFormat("    \"avg_miss_penalty\": %.4f,\n",
                           SafeRatio(dcache_miss_cycles, dcache_miss));
  os << absl::StreamFormat("    \"amat_cycles\": %.6f\n",
                           dcache_access == 0
                               ? 0.0
                               : 1.0 + SafeRatio(dcache_miss_cycles,
                                                 dcache_access));
  os << "  },\n";
  const uint64_t fe_redir_br = Get(kFeRedirectBranch);
  const uint64_t fe_f1_miss  = Get(kFeF1TlbMiss);
  const uint64_t fe_f2_wait  = Get(kFeF2IcacheWait);
  const uint64_t fe_fp_wait  = Get(kFeFpRespWait);

  os << "  \"ifu\": {\n";
  os << "    \"deliver_cycles\": " << ifu_out << ",\n";
  os << "    \"stall_cycles\": " << ifu_stall << ",\n";
  os << absl::StreamFormat("    \"stall_ratio\": %.6f,\n",
                           SafeRatio(ifu_stall, cycles_));
  os << "    \"redirect_branch\": " << fe_redir_br << ",\n";
  os << "    \"f1_tlb_miss_cycles\": " << fe_f1_miss << ",\n";
  os << "    \"f2_icache_wait_cycles\": " << fe_f2_wait << ",\n";
  os << "    \"fp_resp_wait_cycles\": " << fe_fp_wait << "\n";
  os << "  },\n";
  os << "  \"inst_mix\": {\n";
  os << "    \"alu\": " << Get(kCommitAlu) << ",\n";
  os << "    \"mul_div\": " << Get(kCommitMulDiv) << ",\n";
  os << "    \"load\": " << Get(kCommitLoad) << ",\n";
  os << "    \"store\": " << Get(kCommitStore) << ",\n";
  os << "    \"branch\": " << Get(kCommitCfBranch) << ",\n";
  os << "    \"jal\": " << Get(kCommitCfJal) << ",\n";
  os << "    \"jalr\": " << Get(kCommitCfJalr) << ",\n";
  os << "    \"csr\": " << Get(kCommitCsr) << ",\n";
  os << "    \"system\": " << Get(kCommitSystem) << ",\n";
  os << "    \"fence\": " << Get(kCommitFence) << "\n";
  os << "  }\n";
  os << "}\n";
  return static_cast<bool>(os);
}

}  // namespace npc::perf

// DPI-C callback invoked on every clock edge where an RTL perf-event source
// fires.  Keep extremely cheap so enabling it doesn't distort timing much.
extern "C" void npc_perf_event(int8_t id) {
  npc::perf::PerfCounters::Instance().Increment(static_cast<uint8_t>(id));
}
