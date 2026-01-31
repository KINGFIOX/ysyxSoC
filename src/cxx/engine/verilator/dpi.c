#include "cpu/difftest.h"
#include <memory/paddr.h>
#include <cpu/cpu.h>

// MROM 读取函数 - 用于 AXI4MROM 模块
// MROM 基地址: 0x20000000
void mrom_read(int addr, int* data) {
  *data = (int)paddr_read((paddr_t)addr, 4);
}

// Flash 读取函数 - 用于 SPI Flash 模块
// Flash XIP 基地址: 0x30000000
void flash_read(int addr, int* data) {
  *data = (int)paddr_read((paddr_t)addr, 4);
}

void pmem_read_dpi(int en, int addr, int len, int* data) {
  if (!en) {
    *data = 0;
    return;
  }
  *data = (int)paddr_read((paddr_t)addr, len);
}

void pmem_write_dpi(int en, int addr, int strb, int data) {
  if (!en) return;
  for (int i = 0; i < 4; i++) {
    if ((strb >> i) & 1) {
      paddr_write((paddr_t)(addr + i), 1, (word_t)((data >> (i * 8)) & 0xFF));
    }
  }
}

void exception_dpi(int en, int pc, int mcause, int a0, int tval) {
  if (!en) return;
  IFDEF(CONFIG_DIFFTEST, ref_difftest_raise_intr(a0, tval));
  IFDEF(CONFIG_DIFFTEST, difftest_skip_ref());
  switch (mcause) {
    case 2: INV((vaddr_t)pc); break;
    default: NPCTRAP((vaddr_t)pc, a0); break;
  }
}

// CIRCT DPI ABI: en 参数用于条件调用
void difftest_skip_ref_dpi(int en) {
  if (!en) return;
  difftest_skip_ref();
}
