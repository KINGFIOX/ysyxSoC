#include <npc/isa.hh>
#include <npc/cpu_model.hh>
#include <cstring>
#include <optional>

void isa_reg_display() {
  npc::dut().display();
}

static word_t read_csr_by_name(const npc::CpuModel &d, npc::Csr c) {
  switch (c) {
  case npc::Csr::Mstatus:   return d.mstatus();
  case npc::Csr::Mtvec:     return d.mtvec();
  case npc::Csr::Mepc:      return d.mepc();
  case npc::Csr::Mcause:    return d.mcause();
  case npc::Csr::Mtval:     return d.mtval();
  case npc::Csr::Mvendorid: return d.mvendorid();
  case npc::Csr::Marchid:   return d.marchid();
  }
  return 0;
}

static constexpr npc::Csr csr_list[] = {
  npc::Csr::Mstatus, npc::Csr::Mtvec, npc::Csr::Mepc,
  npc::Csr::Mcause, npc::Csr::Mtval, npc::Csr::Mvendorid, npc::Csr::Marchid,
};

std::optional<word_t> isa_reg_str2val(const char *s) {
  auto &d = npc::dut();
  if (std::strcmp(s, "pc") == 0) return d.pc();
  for (int i = 0; i < npc::num_gprs; i++) {
    if (npc::gpr_names[i] == s) return d.gpr(i);
  }
  for (auto c : csr_list) {
    if (npc::csr_name(c) == s) return read_csr_by_name(d, c);
  }
  return std::nullopt;
}
