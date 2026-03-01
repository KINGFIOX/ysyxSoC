#pragma once

#include <npc/common.hh>
#include <npc/isa_traits.hh>
#include <optional>

extern unsigned char isa_logo[];
void init_isa();
void isa_reg_display();
std::optional<word_t> isa_reg_str2val(const char *name);

enum { MMU_DIRECT, MMU_TRANSLATE, MMU_FAIL };
enum { MEM_TYPE_IFETCH, MEM_TYPE_READ, MEM_TYPE_WRITE };
enum { MEM_RET_OK, MEM_RET_FAIL, MEM_RET_CROSS_PAGE };

#define isa_mmu_check(vaddr, len, type) (MMU_DIRECT)
paddr_t isa_mmu_translate(vaddr_t vaddr, int len, int type);

vaddr_t isa_raise_intr(word_t NO, vaddr_t epc);
word_t isa_return_intr();
#define INTR_EMPTY ((word_t)-1)
word_t isa_query_intr();

void isa_difftest_attach();

// ----------- CSR address constants -----------

enum {
  MSTATUS   = 0x0300,
  MTVEC     = 0x0305,
  MEPC      = 0x0341,
  MCAUSE    = 0x0342,
  MTVAL     = 0x0343,
  MVENDORID = 0x0F11,
  MARCHID   = 0x0F12,
};

// ----------- Register helpers -----------

inline int check_reg_idx(int idx) {
  IFDEF(CONFIG_RT_CHECK, assert(idx >= 0 && idx < 32));
  return idx;
}

inline const char *reg_name(int idx) {
  return npc::gpr_names[check_reg_idx(idx)].data();
}
