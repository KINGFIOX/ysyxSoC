#pragma once

#include <npc/common.hh>

#define __EXPORT __attribute__((visibility("default")))

enum { DIFFTEST_TO_DUT, DIFFTEST_TO_REF };

#define RISCV_GPR_TYPE uint32_t
#define RISCV_GPR_NUM  32
#define DIFFTEST_REG_SIZE (sizeof(RISCV_GPR_TYPE) * (RISCV_GPR_NUM + 1))

extern void (*ref_difftest_memcpy)(paddr_t addr, void *buf, size_t n, bool direction);
extern void (*ref_difftest_regcpy)(void *dut, bool direction);
extern void (*ref_difftest_exec)(uint64_t n);
extern void (*ref_difftest_raise_intr)(uint64_t NO, int tval);

#ifdef CONFIG_DIFFTEST
void difftest_skip_ref();
void difftest_skip_dut(int nr_ref, int nr_dut);
bool difftest_step(vaddr_t pc, vaddr_t npc);
void init_difftest(char *ref_so_file, long img_size, int port);
#else
inline void difftest_skip_ref() {}
inline void difftest_skip_dut(int, int) {}
inline bool difftest_step(vaddr_t, vaddr_t) { return true; }
inline void init_difftest(char *, long, int) {}
#endif

inline bool difftest_check_reg(const char *name, vaddr_t pc,
                               word_t ref, word_t dut) {
  if (unlikely(ref != dut)) {
    Log("%s is different after executing instruction at pc = " FMT_WORD
        ", right = " FMT_WORD ", wrong = " FMT_WORD ", diff = " FMT_WORD,
        name, pc, ref, dut, ref ^ dut);
    return false;
  }
  return true;
}
