#include "cpu/verilator_cpu.h"

#include <stdexcept>
#include <string>

#include "VNPCSoC.h"
#include "absl/log/log.h"
#include "absl/strings/str_format.h"
#include "nvboard/nvboard.h"
#include "nvboard/nvboard_bind.h"
#include "verilated.h"
#include "verilated_vcd_c.h"

// Override Verilator's $stop handler to throw instead of abort.
struct VlStopException : std::runtime_error {
  using std::runtime_error::runtime_error;
};

__attribute__((weak)) double sc_time_stamp() { return 0; }

void vl_stop(const char* filename, int linenum, const char* hier) {
  throw VlStopException(std::string(filename) + ":" + std::to_string(linenum) +
                        ": Verilog $stop (in " + hier + ")");
}

namespace npc {

static constexpr const char* kVcdPath = "build/npc_core.vcd";

VerilatorCpu::VerilatorCpu(absl::Span<const uint8_t> flash_data, bool nvboard)
    : ctx_(std::make_unique<VerilatedContext>()), mem_(flash_data) {
  g_memory = &mem_;

  top_ = new VNPCSoC(ctx_.get(), "TOP");
  Verilated::traceEverOn(true);

  if (nvboard) {
    nvboard_ = nvboard_create(top_, 1);
    nvboard_uart_attach(nvboard_.get());
  }

  reset();
}

VerilatorCpu::~VerilatorCpu() {
  nvboard_uart_attach(nullptr);
  nvboard_.reset();
  if (tfp_ != nullptr) {
    tfp_->flush();
    tfp_->close();
    delete tfp_;
  }
  top_->final();
  delete top_;
}

absl::Status VerilatorCpu::tick() {
  top_->clock = 0;
  try {
    top_->eval();
  } catch (const VlStopException& e) {
    return absl::InternalError(
        absl::StrFormat("Verilator $stop triggered: %s", e.what()));
  }
  if (tfp_ != nullptr) tfp_->dump(sim_time_);
  ++sim_time_;

  top_->clock = 1;
  try {
    top_->eval();
  } catch (const VlStopException& e) {
    return absl::InternalError(
        absl::StrFormat("Verilator $stop triggered: %s", e.what()));
  }
  if (tfp_ != nullptr) tfp_->dump(sim_time_);
  ++sim_time_;

  if (nvboard_) {
    nvboard_->Update();
  }

  if (tfp_ != nullptr && wave_tail_ > 0) {
    ++wave_cycle_;
    if (wave_cycle_ >= wave_tail_) {
      tfp_->close();
      tfp_->open(kVcdPath);
      wave_cycle_ = 0;
    }
  }

  return absl::OkStatus();
}

absl::Status VerilatorCpu::run_until(uint64_t target_sim_time) {
  while (sim_time_ < target_sim_time) {
    auto s = tick();
    if (!s.ok()) return s;
  }
  return absl::OkStatus();
}

void VerilatorCpu::enable_wave(uint64_t tail) {
  if (tfp_ != nullptr) return;
  wave_tail_ = tail;
  wave_cycle_ = 0;
  tfp_ = new VerilatedVcdC;
  top_->trace(tfp_, 99);
  tfp_->open(kVcdPath);
}

// ========================== Probe signals ==========================

bool VerilatorCpu::is_mmio() const { return top_->probe_bits_is_mmio != 0; }

uint64_t VerilatorCpu::dnpc() const { return top_->probe_bits_dnpc; }

uint32_t VerilatorCpu::inst() const { return top_->probe_bits_inst; }

uint32_t VerilatorCpu::perf_commit_cnt() const {
  return top_->probe_bits_perf_commit_cnt;
}

uint32_t VerilatorCpu::perf_branch_cnt() const {
  return top_->probe_bits_perf_branch_cnt;
}

uint32_t VerilatorCpu::perf_branch_mispredict_cnt() const {
  return top_->probe_bits_perf_branch_mispredict_cnt;
}

uint32_t VerilatorCpu::perf_flush_cnt() const {
  return top_->probe_bits_perf_flush_cnt;
}

// ========================== CpuRegView ==========================

uint64_t VerilatorCpu::pc() const { return top_->probe_bits_pc; }

absl::StatusOr<uint64_t> VerilatorCpu::gpr(int index) const {
  if (index < 0 || index >= 32) {
    return absl::InvalidArgumentError("invalid register index");
  }
  // Direct access to probe signals -- no bridge needed.
  switch (index) {
    case 0:
      return top_->probe_bits_gpr_0;
    case 1:
      return top_->probe_bits_gpr_1;
    case 2:
      return top_->probe_bits_gpr_2;
    case 3:
      return top_->probe_bits_gpr_3;
    case 4:
      return top_->probe_bits_gpr_4;
    case 5:
      return top_->probe_bits_gpr_5;
    case 6:
      return top_->probe_bits_gpr_6;
    case 7:
      return top_->probe_bits_gpr_7;
    case 8:
      return top_->probe_bits_gpr_8;
    case 9:
      return top_->probe_bits_gpr_9;
    case 10:
      return top_->probe_bits_gpr_10;
    case 11:
      return top_->probe_bits_gpr_11;
    case 12:
      return top_->probe_bits_gpr_12;
    case 13:
      return top_->probe_bits_gpr_13;
    case 14:
      return top_->probe_bits_gpr_14;
    case 15:
      return top_->probe_bits_gpr_15;
    case 16:
      return top_->probe_bits_gpr_16;
    case 17:
      return top_->probe_bits_gpr_17;
    case 18:
      return top_->probe_bits_gpr_18;
    case 19:
      return top_->probe_bits_gpr_19;
    case 20:
      return top_->probe_bits_gpr_20;
    case 21:
      return top_->probe_bits_gpr_21;
    case 22:
      return top_->probe_bits_gpr_22;
    case 23:
      return top_->probe_bits_gpr_23;
    case 24:
      return top_->probe_bits_gpr_24;
    case 25:
      return top_->probe_bits_gpr_25;
    case 26:
      return top_->probe_bits_gpr_26;
    case 27:
      return top_->probe_bits_gpr_27;
    case 28:
      return top_->probe_bits_gpr_28;
    case 29:
      return top_->probe_bits_gpr_29;
    case 30:
      return top_->probe_bits_gpr_30;
    case 31:
      return top_->probe_bits_gpr_31;
    default:
      return uint64_t{0};
  }
}

uint64_t VerilatorCpu::mstatus() const { return top_->probe_bits_csr_mstatus; }
uint64_t VerilatorCpu::mtvec() const { return top_->probe_bits_csr_mtvec; }
uint64_t VerilatorCpu::mepc() const { return top_->probe_bits_csr_mepc; }
uint64_t VerilatorCpu::mcause() const { return top_->probe_bits_csr_mcause; }
uint64_t VerilatorCpu::mtval() const { return top_->probe_bits_csr_mtval; }
uint64_t VerilatorCpu::medeleg() const {
  return top_->probe_bits_csr_medeleg;
}
uint64_t VerilatorCpu::mideleg() const {
  return top_->probe_bits_csr_mideleg;
}
uint64_t VerilatorCpu::mie() const { return top_->probe_bits_csr_mie; }
uint64_t VerilatorCpu::mscratch() const {
  return top_->probe_bits_csr_mscratch;
}
uint64_t VerilatorCpu::menvcfg() const {
  return top_->probe_bits_csr_menvcfg;
}
uint64_t VerilatorCpu::mcounteren() const {
  return top_->probe_bits_csr_mcounteren;
}
uint64_t VerilatorCpu::pmpcfg0() const {
  return top_->probe_bits_csr_pmpcfg0;
}
uint64_t VerilatorCpu::pmpaddr0() const {
  return top_->probe_bits_csr_pmpaddr0;
}

uint64_t VerilatorCpu::stvec() const { return top_->probe_bits_csr_stvec; }
uint64_t VerilatorCpu::sepc() const { return top_->probe_bits_csr_sepc; }
uint64_t VerilatorCpu::scause() const { return top_->probe_bits_csr_scause; }
uint64_t VerilatorCpu::stval() const { return top_->probe_bits_csr_stval; }
uint64_t VerilatorCpu::sscratch() const {
  return top_->probe_bits_csr_sscratch;
}
uint64_t VerilatorCpu::satp() const { return top_->probe_bits_csr_satp; }
uint64_t VerilatorCpu::stimecmp() const {
  return top_->probe_bits_csr_stimecmp;
}

uint64_t VerilatorCpu::mvendorid() const {
  return top_->probe_bits_csr_mvendorid;
}
uint64_t VerilatorCpu::marchid() const { return top_->probe_bits_csr_marchid; }
uint64_t VerilatorCpu::mhartid() const { return top_->probe_bits_csr_mhartid; }

absl::StatusOr<uint64_t> VerilatorCpu::mem_load(uint64_t addr,
                                                uint8_t width) const {
  return mem_.load(addr, width);
}

void VerilatorCpu::reset() {
  top_->reset = 1;
  for (int i = 0; i < kResetCycles; ++i) {
    auto s = tick();
    if (!s.ok()) {
      LOG(FATAL) << "tick failed during reset: " << s;
    }
  }
  top_->reset = 0;
}

absl::Status VerilatorCpu::step() {
  for (int i = 0; i < kMaxStepCycles; ++i) {
    auto s = tick();
    if (!s.ok()) return s;
    if (top_->probe_valid != 0) {
      return absl::OkStatus();
    }
  }
  return absl::DeadlineExceededError(absl::StrFormat(
      "step exceeded %d cycles without probe_valid", kMaxStepCycles));
}

}  // namespace npc
