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

#define FLASH_BASE CONFIG_SOC_XIP_FLASH_BASE
#define FLASH_SIZE CONFIG_SOC_XIP_FLASH_SIZE

/* Flash file handle for on-demand reading */
static FILE *flash_fp = NULL;
static long flash_file_size = 0;

void init_flash(const char *flash_file) {
  if (flash_file == NULL) {
    Log("No flash file specified, flash reads will return 0xFF");
    return;
  }
  flash_fp = fopen(flash_file, "rb");
  if (flash_fp == NULL) {
    Log("Warning: Cannot open flash file '%s', flash reads will return 0xFF", flash_file);
    return;
  }
  /* Get file size */
  fseek(flash_fp, 0, SEEK_END); flash_file_size = ftell(flash_fp);
  fseek(flash_fp, 0, SEEK_SET);
  Log("Flash initialized from file: %s (size = %ld bytes)", flash_file, flash_file_size);
  Log("Flash area [" FMT_PADDR ", " FMT_PADDR "]", (paddr_t)FLASH_BASE, (paddr_t)(FLASH_BASE + FLASH_SIZE - 1));
}

/**
 * @param addr: addr starts from 0, aligned to 4bytes
 */
void flash_read(int addr, int *data) {
  /* Check if flash file is loaded */
  if (flash_fp == NULL) { return; }
  /* Check bounds */
  if (addr >= FLASH_SIZE) {
    Log("Warning: Flash read out of bounds at offset 0x%08x", addr);
    return;
  }

  /* Read 4 bytes from the file at the given offset */
  uint8_t buf[4] = {0xFF, 0xFF, 0xFF, 0xFF};

  /* Seek to the offset in the file */
  if (fseek(flash_fp, addr, SEEK_SET) != 0) {
    *data = 0xFFFFFFFF;
    return;
  }

  /* Read up to 4 bytes (may read less if near end of file) */
  size_t bytes_to_read = 4;
  if (addr + 4 > (uint32_t)flash_file_size) {
    bytes_to_read = (addr < (uint32_t)flash_file_size) ? (flash_file_size - addr) : 0;
  }
  if (bytes_to_read > 0) {
    size_t read = fread(buf, 1, bytes_to_read, flash_fp);
    (void)read;  /* Ignore return value, buf already initialized to 0xFF */
  }

  /* Combine bytes in little-endian order */
  *data = (int)(buf[0] | (buf[1] << 8) | (buf[2] << 16) | (buf[3] << 24));
}

/* Check if address is in flash range */
bool in_flash(paddr_t addr) {
  return addr >= FLASH_BASE && addr < FLASH_BASE + FLASH_SIZE;
}
