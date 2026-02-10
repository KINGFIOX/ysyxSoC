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
#include <stdlib.h>

#define FLASH_BASE CONFIG_SOC_XIP_FLASH_BASE
#define FLASH_SIZE CONFIG_SOC_XIP_FLASH_SIZE

static const uint32_t builtin_img[] = {
    0x00000297, // auipc t0,0
    0x00028823, // sb  zero,16(t0)
    0x0102c503, // lbu a0,16(t0)
    0x00100073, // ebreak (used as npc_trap)
    0xdeadbeef, // some data
};

static uint8_t *flash_mem = NULL;
static long flash_loaded_size = 0;

/* Initialize Flash and load image (替代原来的 MROM) */
long init_flash(const char *img_file) {
  Log("Flash area [" FMT_PADDR ", " FMT_PADDR "]", (paddr_t)FLASH_BASE,
      (paddr_t)(FLASH_BASE + FLASH_SIZE - 1));

  if (img_file == NULL) {
    Log("No image is given. Use the default built-in image.");
    flash_loaded_size = sizeof(builtin_img);
    flash_mem = malloc(flash_loaded_size);
    memcpy(flash_mem, builtin_img, flash_loaded_size);
    return flash_loaded_size;
  }

  FILE *fp = fopen(img_file, "rb");
  Assert(fp, "Can not open '%s'", img_file);

  fseek(fp, 0, SEEK_END);
  long size = ftell(fp);

  Log("The image is %s, size = %ld", img_file, size);

  if (size > FLASH_SIZE) {
    Log("Warning: image size %ld exceeds Flash size %d, truncated", size,
        FLASH_SIZE);
    size = FLASH_SIZE;
  }

  flash_mem = malloc(size);
  fseek(fp, 0, SEEK_SET);
  int ret = fread(flash_mem, size, 1, fp);
  assert(ret == 1);

  fclose(fp);
  flash_loaded_size = size;
  return size;
}

/**
 * @param addr: addr starts from 0, aligned to 4bytes (offset within flash)
 */
void flash_read(int addr, int *data) {
  if (flash_mem == NULL) {
    *data = 0;
    return;
  }
  /* Check bounds */
  if (addr < 0 || addr >= FLASH_SIZE) {
    Log("Warning: Flash read out of bounds at offset 0x%08x", addr);
    *data = 0;
    return;
  }

  uint8_t buf[4] = { };
  for (int i = 0; i < 4 && (addr + i) < flash_loaded_size; i++) {
    buf[i] = flash_mem[addr + i];
  }

  /* Combine bytes in little-endian order */
  *data = (int)(buf[0] | (buf[1] << 8) | (buf[2] << 16) | (buf[3] << 24));
  // Log("flash_read(addr=0x%08x) -> data=0x%08x", addr, *data);
}

/* Check if address is in flash range */
bool in_flash(paddr_t addr) {
  return addr >= FLASH_BASE && addr < FLASH_BASE + FLASH_SIZE;
}

/* 获取 flash 指针，供 difftest 使用 */
uint8_t *get_flash_ptr(void) { return flash_mem; }
