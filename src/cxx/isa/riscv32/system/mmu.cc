#include <npc/isa.hh>

paddr_t isa_mmu_translate(vaddr_t vaddr, int len, int type) {
  (void)vaddr;
  (void)len;
  (void)type;
  return MEM_RET_FAIL;
}
