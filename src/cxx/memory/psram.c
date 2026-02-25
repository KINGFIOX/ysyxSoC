#include <common.h>
#include <stdio.h>
#include <stdlib.h>

#define PSRAM_BASE CONFIG_SOC_PSRAM_BASE
#define PSRAM_SIZE CONFIG_SOC_PSRAM_SIZE

static uint8_t psram_mem[PSRAM_SIZE];

/**
 * @param addr: addr starts from 0, aligned to 4bytes (offset within psram)
 */
void psram_read(int addr, char *data) {
  /* Check bounds */
  if (addr < 0 || addr >= PSRAM_SIZE) {
    Log("Warning: PSRAM read out of bounds at offset 0x%08x", addr);
    *data = 0;
    return;
  }
  char byte = psram_mem[addr];
  // Log("psram_read(addr=0x%08x) -> data=0x%02x", addr, byte);
  *data = byte;
}

void psram_write(int addr, char data) {
  /* Check bounds */
  if (addr < 0 || addr >= PSRAM_SIZE) {
    Log("Warning: PSRAM read out of bounds at offset 0x%08x", addr);
    return;
  }
  // Log("psram_write(addr=0x%08x) -> data=0x%02x", addr, data);
  psram_mem[addr] = data;
}

/* Check if address is in PSRAM range */
bool in_psram(paddr_t addr) {
  return addr >= PSRAM_BASE && addr < PSRAM_BASE + PSRAM_SIZE;
}
