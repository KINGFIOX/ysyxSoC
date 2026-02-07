/***************************************************************************************
 * Copyright (c) 2014-2024 Zihao Yu, Nanjing University
 *
 * NPC is licensed under Mulan PSL v2.
 * You can use this software according to the terms and conditions of the Mulan
 *PSL v2. You may obtain a copy of Mulan PSL v2 at:
 *          http://license.coscl.org.cn/MulanPSL2
 *
 * THIS SOFTWARE IS PROVIDED ON AN "AS IS" BASIS, WITHOUT WARRANTIES OF ANY
 *KIND, EITHER EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO
 *NON-INFRINGEMENT, MERCHANTABILITY OR FIT FOR A PARTICULAR PURPOSE.
 *
 * See the Mulan PSL v2 for more details.
 ***************************************************************************************/

#include <common.h>
#include <stdio.h>

#define MROM_BASE CONFIG_SOC_MROM_BASE
#define MROM_SIZE CONFIG_SOC_MROM_SIZE

static const uint32_t builtin_img[] = {
    0x00000297, // auipc t0,0
    0x00028823, // sb  zero,16(t0)
    0x0102c503, // lbu a0,16(t0)
    0x00100073, // ebreak (used as npc_trap)
    0xdeadbeef, // some data
};

static uint8_t mrom[MROM_SIZE] PG_ALIGN = {};
static size_t mrom_loaded_size = 0;

bool in_mrom(paddr_t addr) {
  return addr >= MROM_BASE && addr < MROM_BASE + MROM_SIZE;
}

// 初始化 MROM 并加载镜像（符合 MROM 只读语义：初始化即加载）
long init_mrom(const char *img_file) {
  Log("mrom area [" FMT_PADDR ", " FMT_PADDR "]",
      (paddr_t)MROM_BASE, (paddr_t)(MROM_BASE + MROM_SIZE - 1));

  if (img_file == NULL) {
    Log("No image is given. Use the default build-in image.");
    memcpy(mrom, builtin_img, sizeof(builtin_img));
    mrom_loaded_size = sizeof(builtin_img);
    return mrom_loaded_size;
  }

  FILE *fp = fopen(img_file, "rb");
  Assert(fp, "Can not open '%s'", img_file);

  fseek(fp, 0, SEEK_END);
  long size = ftell(fp);

  Log("The image is %s, size = %ld", img_file, size);

  if (size > MROM_SIZE) {
    Log("Warning: image size %ld exceeds MROM size %d, truncated", size, MROM_SIZE);
    size = MROM_SIZE;
  }

  fseek(fp, 0, SEEK_SET);
  int ret = fread(mrom, size, 1, fp);
  assert(ret == 1);

  fclose(fp);
  mrom_loaded_size = size;
  return size;
}

// 统一的 MROM 读取接口，同时作为 DPI 接口和 paddr 读取
void mrom_read(int addr, int *data) {
  uint32_t offset = (uint32_t)addr - MROM_BASE;
  if (offset < mrom_loaded_size && offset + 4 <= MROM_SIZE) {
    *data = (int)(mrom[offset] |
                  (mrom[offset + 1] << 8) |
                  (mrom[offset + 2] << 16) |
                  (mrom[offset + 3] << 24));
  } else {
    *data = 0x00100073;  // ebreak 作为默认值
  }
}

// MROM 是只读的
void mrom_write(paddr_t addr, int len, word_t data) {
  panic("MROM is read-only, cannot write to address " FMT_PADDR, addr);
}

// 获取 mrom 指针，供 difftest 使用
uint8_t *get_mrom_ptr(void) {
  return mrom;
}
