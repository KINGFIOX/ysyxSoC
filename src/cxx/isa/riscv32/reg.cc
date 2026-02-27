#include <npc/isa.hh>
#include <cstring>
#include <optional>
#include <utility>

void isa_reg_display() {
  for (int i = 0; i < npc::num_gprs; i++) {
    std::printf("%-4.*s\t" FMT_WORD "\n",
                static_cast<int>(npc::gpr_names[i].size()),
                npc::gpr_names[i].data(), cpu.gpr[i]);
  }
  std::printf("pc\t" FMT_WORD "\n", cpu.pc);
}

static constexpr std::pair<npc::Csr, uint16_t> csr_entries[] = {
  {npc::Csr::Mstatus, MSTATUS}, {npc::Csr::Mtvec, MTVEC},
  {npc::Csr::Mepc, MEPC}, {npc::Csr::Mcause, MCAUSE},
  {npc::Csr::Mtval, MTVAL}, {npc::Csr::Mvendorid, MVENDORID},
  {npc::Csr::Marchid, MARCHID},
};

std::optional<word_t> isa_reg_str2val(const char *s) {
  if (std::strcmp(s, "pc") == 0) return cpu.pc;
  for (int i = 0; i < npc::num_gprs; i++) {
    if (npc::gpr_names[i] == s) return cpu.gpr[i];
  }
  for (auto &[csr_enum, csr_idx] : csr_entries) {
    if (npc::csr_name(csr_enum) == s) return cpu.csr[csr_idx];
  }
  return std::nullopt;
}
