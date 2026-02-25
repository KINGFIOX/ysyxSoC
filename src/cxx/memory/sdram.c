#include <common.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>

#define SDRAM_BASE CONFIG_SOC_SDRAM_BASE
#define SDRAM_SIZE CONFIG_SOC_SDRAM_SIZE

static uint8_t sdram_mem[SDRAM_SIZE];

/**
 * @param addr: addr starts from 0, aligned to 4bytes (offset within sdram)
 */
void sdram_read(int addr, uint16_t *ret) {
  /* Check bounds */
  if (addr < 0 || addr >= SDRAM_SIZE) {
    Log("Warning: SDRAM read out of bounds at offset 0x%08x", addr);
    *ret = 0;
    return;
  }
  uint8_t byte0 = sdram_mem[addr];
  uint8_t byte1 = sdram_mem[addr + 1];
  uint16_t half = (byte1 << 8) | byte0;
  // Log("sdram_read(addr=0x%08x) -> data=0x%04x", addr, half);
  *ret = half;
}

uint16_t sdram_read_dpic(int addr) {
  uint16_t half;
  sdram_read(addr, &half);
  return half;
}

void sdram_write(int addr, uint8_t data) {
  /* Check bounds */
  if (addr < 0 || addr >= SDRAM_SIZE) {
    Log("Warning: SDRAM write out of bounds at offset 0x%08x", addr);
    return;
  }
  // Log("sdram_write(addr=0x%08x) -> data=0x%02x", addr, data);
  sdram_mem[addr] = data;
}

/* Check if address is in SDRAM range */
bool in_sdram(paddr_t addr) {
  return addr >= SDRAM_BASE && addr < SDRAM_BASE + SDRAM_SIZE;
}
