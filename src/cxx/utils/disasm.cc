#include <capstone/capstone.h>
#include <npc/common.hh>
#include <cstdio>

static csh handle;

void init_disasm() {
  cs_arch arch = CS_ARCH_RISCV;
  cs_mode mode = static_cast<cs_mode>(CS_MODE_RISCV32 | CS_MODE_RISCVC);
  int ret = cs_open(arch, mode, &handle);
  assert(ret == CS_ERR_OK);
}

bool disassemble(char *str, int size, uint64_t pc, uint8_t *code, int nbyte) {
  cs_insn *insn;
  size_t count = cs_disasm(handle, code, nbyte, pc, 1, &insn);
  if (count != 1) return false;
  int ret = std::snprintf(str, size, "%s", insn->mnemonic);
  if (insn->op_str[0] != '\0')
    std::snprintf(str + ret, size - ret, "\t%s", insn->op_str);
  cs_free(insn, count);
  return true;
}
