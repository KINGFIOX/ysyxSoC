#include <common.h>
#include <stdio.h>
#include <stdlib.h>

#define SDRAM_BASE CONFIG_SOC_SDRAM_BASE
#define SDRAM_SIZE CONFIG_SOC_SDRAM_SIZE

static uint8_t sdram_mem[SDRAM_SIZE];

/**
 * @param addr: addr starts from 0, aligned to 4bytes (offset within sdram)
 */
void sdram_read(int addr, char *data) {
  /* Check bounds */
  if (addr < 0 || addr >= SDRAM_SIZE) {
    Log("Warning: SDRAM read out of bounds at offset 0x%08x", addr);
    *data = 0;
    return;
  }
  char byte = sdram_mem[addr];
  // Log("sdram_read(addr=0x%08x) -> data=0x%02x", addr, byte);
  *data = byte;
}

void sdram_write(int addr, char data) {
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
