#include "cpu/difftest.h"
#include <cpu/cpu.h>
#include <stddef.h>
#include <stdint.h>
#include <string.h>

#define MROM_BASE  0x20000000
#define MROM_SIZE  0x1000

static uint8_t mrom_content[MROM_SIZE];
static size_t mrom_content_size = 0;

void mrom_load_image(const void *data, size_t size) {
  if (size > MROM_SIZE) size = MROM_SIZE;
  memcpy(mrom_content, data, size);
  mrom_content_size = size;
}

void mrom_read(int addr, int *data) {
  uint32_t offset = (uint32_t)addr - MROM_BASE;
  if (offset < mrom_content_size && offset + 4 <= MROM_SIZE) {
    *data = (int)(mrom_content[offset] |
                  (mrom_content[offset + 1] << 8) |
                  (mrom_content[offset + 2] << 16) |
                  (mrom_content[offset + 3] << 24));
  } else {
    *data = 0x00100073;  // ebreak 作为默认值
  }
}

// Flash 读取函数 - 用于 SPI Flash 模块
// Flash XIP 基地址: 0x30000000
void flash_read(int addr, int* data) {
  *data = (int)vaddr_read((paddr_t)addr, 4);
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
