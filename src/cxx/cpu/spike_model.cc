#include <npc/cpu_model.hh>
#include <npc/cpu.hh>
#include <npc/difftest.hh>

#include <sys/syscall.h>
#include "mmu.h"
#include "sim.h"

#define ALIGN_4K(size) (((size) + 0xFFF) & ~0xFFF)

void sim_t::diff_init(void) {
  auto *p = get_core("0");
  auto *st = p->get_state();
  st->pc = CONFIG_SOC_RESET_VECTOR;
}

void sim_t::diff_step(uint64_t n) {
  step(n);
}

void sim_t::diff_memcpy(reg_t dest, void* src, size_t n) {
  auto *mmu = get_core("0")->get_mmu();
  auto *bytes = static_cast<uint8_t*>(src);
  for (size_t i = 0; i < n; i++) {
    mmu->store<uint8_t>(dest + i, bytes[i]);
}
}

namespace npc {

class SpikeModel final : public CpuModel {
  sim_t *sim_ = nullptr;
  processor_t *proc_ = nullptr;
  state_t *spike_state_ = nullptr;
  CpuState state_{};

  static std::vector<std::pair<reg_t, mem_t*>> make_mem_map() {
    return {
      std::make_pair(reg_t(CONFIG_SOC_MROM_BASE), new mem_t(ALIGN_4K(CONFIG_SOC_MROM_SIZE))),
      std::make_pair(reg_t(CONFIG_SOC_SRAM_BASE), new mem_t(ALIGN_4K(CONFIG_SOC_SRAM_SIZE))),
      std::make_pair(reg_t(CONFIG_SOC_UART_BASE), new mem_t(ALIGN_4K(CONFIG_SOC_UART_SIZE))),
#ifdef CONFIG_SOC_GPIO_BASE
      std::make_pair(reg_t(CONFIG_SOC_GPIO_BASE), new mem_t(ALIGN_4K(CONFIG_SOC_GPIO_SIZE))),
#endif
#ifdef CONFIG_SOC_KEYBOARD_BASE
      std::make_pair(reg_t(CONFIG_SOC_KEYBOARD_BASE), new mem_t(ALIGN_4K(CONFIG_SOC_KEYBOARD_SIZE))),
#endif
#ifdef CONFIG_SOC_VGA_BASE
      std::make_pair(reg_t(CONFIG_SOC_VGA_BASE), new mem_t(ALIGN_4K(CONFIG_SOC_VGA_SIZE))),
#endif
#ifdef CONFIG_SOC_SPI_CTRL_BASE
      std::make_pair(reg_t(CONFIG_SOC_SPI_CTRL_BASE), new mem_t(ALIGN_4K(CONFIG_SOC_SPI_CTRL_SIZE))),
#endif
#ifdef CONFIG_SOC_XIP_FLASH_BASE
      std::make_pair(reg_t(CONFIG_SOC_XIP_FLASH_BASE), new mem_t(ALIGN_4K(CONFIG_SOC_XIP_FLASH_SIZE))),
#endif
#ifdef CONFIG_SOC_PSRAM_BASE
      std::make_pair(reg_t(CONFIG_SOC_PSRAM_BASE), new mem_t(ALIGN_4K(CONFIG_SOC_PSRAM_SIZE))),
#endif
#ifdef CONFIG_SOC_SDRAM_BASE
      std::make_pair(reg_t(CONFIG_SOC_SDRAM_BASE), new mem_t(ALIGN_4K(CONFIG_SOC_SDRAM_SIZE))),
#endif
    };
  }

  void sync_from_spike() {
    state_.pc = spike_state_->pc;
    for (int i = 0; i < num_gprs; i++)
      state_.gpr[i] = spike_state_->XPR[i];
    state_.mstatus = proc_->get_csr(MSTATUS);
    state_.mtvec   = proc_->get_csr(MTVEC);
    state_.mepc    = proc_->get_csr(MEPC);
    state_.mcause  = proc_->get_csr(MCAUSE);
    state_.mtval   = proc_->get_csr(MTVAL);
    state_.mvendorid = proc_->get_csr(MVENDORID);
    state_.marchid   = proc_->get_csr(MARCHID);
  }

  void sync_to_spike() {
    spike_state_->pc = state_.pc;
    for (int i = 0; i < num_gprs; i++) {
      spike_state_->XPR.write(i, state_.gpr[i]);
}
    proc_->put_csr(MSTATUS, state_.mstatus);
    proc_->put_csr(MTVEC,   state_.mtvec);
    proc_->put_csr(MEPC,    state_.mepc);
    proc_->put_csr(MCAUSE,  state_.mcause);
    proc_->put_csr(MTVAL,   state_.mtval);
  }

public:
  explicit SpikeModel() {}

  bool init() override {
    std::vector<std::string> htif_args{""};
    std::vector<std::pair<reg_t, abstract_device_t*>> plugin_devices;
    auto mem = make_mem_map();

    static debug_module_config_t dm_config = {
      2, 0, false, 0, true, true, true, true, true
    };

    const char *isa = "RV32IMAFDC";
    auto *cfg = new cfg_t(
      std::make_pair(reg_t(0), reg_t(0)),
      nullptr, isa, DEFAULT_PRIV, DEFAULT_VARCH,
      false, endianness_little, 16,
      std::vector<mem_cfg_t>(),
      std::vector<size_t>(1),
      false, 4);

    sim_ = new sim_t(cfg, false,
      mem, plugin_devices, htif_args,
      dm_config, nullptr, false, nullptr,
      false, nullptr, true);

    sim_->diff_init();
    proc_ = sim_->get_core(0);
    spike_state_ = proc_->get_state();

    state_.pc = RESET_VECTOR;
    return true;
  }

  void fini() override {
    delete sim_;
    sim_ = nullptr;
    proc_ = nullptr;
    spike_state_ = nullptr;
  }

  StepResult step() override {
    sim_->diff_step(1);
    sync_from_spike();
    return {state_.pc, state_.pc, 0, false};
  }

  [[nodiscard]] word_t pc() const override { return state_.pc; }
  void set_pc(word_t v) override { state_.pc = v; }

  [[nodiscard]] word_t gpr(int i) const override { return state_.gpr.at(i); }
  void set_gpr(int i, word_t v) override { state_.gpr.at(i) = v; }

  [[nodiscard]] std::span<const word_t, num_gprs> gprs() const override {
    return state_.gpr;
  }
  std::span<word_t, num_gprs> gprs_mut() override {
    return state_.gpr;
  }

  [[nodiscard]] word_t mstatus() const override { return state_.mstatus; }
  void set_mstatus(word_t v) override { state_.mstatus = v; }
  [[nodiscard]] word_t mtvec() const override { return state_.mtvec; }
  void set_mtvec(word_t v) override { state_.mtvec = v; }
  [[nodiscard]] word_t mepc() const override { return state_.mepc; }
  void set_mepc(word_t v) override { state_.mepc = v; }
  [[nodiscard]] word_t mcause() const override { return state_.mcause; }
  void set_mcause(word_t v) override { state_.mcause = v; }
  [[nodiscard]] word_t mtval() const override { return state_.mtval; }
  void set_mtval(word_t v) override { state_.mtval = v; }
  [[nodiscard]] word_t mvendorid() const override { return state_.mvendorid; }
  [[nodiscard]] word_t marchid() const override { return state_.marchid; }

  void memcpy(paddr_t addr, void *buf, size_t n, bool direction) override {
    if (direction == DIFFTEST_TO_REF) {
      sim_->diff_memcpy(addr, buf, n);
    } else {
      panic("SpikeModel::memcpy DIFFTEST_TO_DUT not supported");
    }
  }

  void raise_intr(word_t no, word_t tval) override {
    insn_trap_t t(no, false, tval);
    proc_->take_trap_public(t, spike_state_->pc);
    sync_from_spike();
  }

  void display() const override {
    for (int i = 0; i < num_gprs; i++) {
      std::printf("%-4s = " FMT_WORD "  ", gpr_names[i].data(), state_.gpr[i]);
      if ((i + 1) % 4 == 0) std::printf("\n");
    }
    std::printf("pc   = " FMT_WORD "\n", state_.pc);
  }

  void sync_state_from(CpuModel &src) {
    for (int i = 0; i < num_gprs; i++)
      state_.gpr[i] = src.gpr(i);
    state_.pc = src.pc();
    state_.mstatus = src.mstatus();
    state_.mtvec   = src.mtvec();
    state_.mepc    = src.mepc();
    state_.mcause  = src.mcause();
    state_.mtval   = src.mtval();
    sync_to_spike();
  }
};

} // namespace npc

static npc::SpikeModel *g_spike = nullptr;

void init_difftest(long img_size, int port) {
  g_spike = new npc::SpikeModel(port);
  npc::set_ref(g_spike);
  g_spike->init();

  Log("Differential testing: {}", ANSI_FMT("ON", ANSI_FG_GREEN));
  Log("The result of every instruction will be compared with SPIKE. "
      "This will help you a lot for debugging, but also significantly reduce "
      "the performance. "
      "If it is not necessary, you can turn it off in menuconfig.");

  uint8_t *get_flash_ptr();
  g_spike->memcpy(RESET_VECTOR, get_flash_ptr(), img_size, DIFFTEST_TO_REF);
  g_spike->sync_state_from(npc::dut());
}
