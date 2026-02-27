#pragma once

#include <cstdint>
#include <cinttypes>
#include <cstring>
#include <cassert>
#include <cstdlib>
#include <cstdio>

#include <generated/autoconf.h>
#include <npc/macro.hh>

#if CONFIG_SOC_XIP_FLASH_BASE + CONFIG_SOC_XIP_FLASH_SIZE > 0x100000000ul || \
    CONFIG_SOC_SRAM_BASE + CONFIG_SOC_SRAM_SIZE > 0x100000000ul
#define PMEM64 1
#endif

using word_t  = uint32_t;
using sword_t = int32_t;
using vaddr_t = word_t;
using paddr_t = MUXDEF(PMEM64, uint64_t, uint32_t);
using ioaddr_t = uint16_t;

#define FMT_WORD  "0x%08" PRIx32
#define FMT_PADDR MUXDEF(PMEM64, "0x%016" PRIx64, "0x%08" PRIx32)

// ----------- ANSI colors -----------

#define ANSI_FG_BLACK   "\33[1;30m"
#define ANSI_FG_RED     "\33[1;31m"
#define ANSI_FG_GREEN   "\33[1;32m"
#define ANSI_FG_YELLOW  "\33[1;33m"
#define ANSI_FG_BLUE    "\33[1;34m"
#define ANSI_FG_MAGENTA "\33[1;35m"
#define ANSI_FG_CYAN    "\33[1;36m"
#define ANSI_FG_WHITE   "\33[1;37m"
#define ANSI_BG_BLACK   "\33[1;40m"
#define ANSI_BG_RED     "\33[1;41m"
#define ANSI_BG_GREEN   "\33[1;42m"
#define ANSI_BG_YELLOW  "\33[1;43m"
#define ANSI_BG_BLUE    "\33[1;44m"
#define ANSI_BG_MAGENTA "\33[1;45m"
#define ANSI_BG_CYAN    "\33[1;46m"
#define ANSI_BG_WHITE   "\33[1;47m"
#define ANSI_NONE       "\33[0m"

#define ANSI_FMT(s, fmt) fmt s ANSI_NONE

// ----------- Simulation state -----------

enum { NPC_RUNNING, NPC_STOP, NPC_END, NPC_ABORT, NPC_QUIT };

struct NPCState {
  int state;
  vaddr_t halt_pc;
  uint32_t halt_ret;
};

extern NPCState npc_state;

// ----------- Log / Timer -----------

bool log_enable();
uint64_t get_time();
void init_rand();
void assert_fail_msg();

#include <npc/log.hh>

// ----------- Debug macros -----------

#define Log(format, ...)                                                       \
  npc::log_impl(ANSI_FMT("[{}:{} {}] " format, ANSI_FG_BLUE) "\n", __FILE__,   \
                __LINE__, __func__, ##__VA_ARGS__)

#define _Log(format, ...) npc::log_raw(format, ##__VA_ARGS__)

#define Assert(cond, format, ...)                                              \
  do {                                                                         \
    if (!(cond)) {                                                             \
      std::fflush(stdout);                                                     \
      std::fprintf(stderr, ANSI_FMT(format, ANSI_FG_RED) "\n",                \
                   ##__VA_ARGS__);                                             \
      assert_fail_msg();                                                       \
      assert(cond);                                                            \
    }                                                                          \
  } while (0)

#define panic(format, ...) Assert(0, format, ##__VA_ARGS__)
#define TODO() panic("please implement me")

// ----------- Host memory access -----------

inline word_t host_read(void *addr, int len) {
  switch (len) {
  case 1: return *static_cast<uint8_t *>(addr);
  case 2: return *static_cast<uint16_t *>(addr);
  case 4: return *static_cast<uint32_t *>(addr);
  default: MUXDEF(CONFIG_RT_CHECK, assert(0), return 0);
  }
}

inline void host_write(void *addr, int len, word_t data) {
  switch (len) {
  case 1: *static_cast<uint8_t *>(addr)  = data; return;
  case 2: *static_cast<uint16_t *>(addr) = data; return;
  case 4: *static_cast<uint32_t *>(addr) = data; return;
  default: MUXDEF(CONFIG_RT_CHECK, assert(0), (void)0);
  }
}

// ----------- Physical address map -----------

#define FLASH_LEFT  ((paddr_t)CONFIG_SOC_XIP_FLASH_BASE)
#define FLASH_RIGHT ((paddr_t)CONFIG_SOC_XIP_FLASH_BASE + CONFIG_SOC_XIP_FLASH_SIZE - 1)
#define SRAM_LEFT   ((paddr_t)CONFIG_SOC_SRAM_BASE)
#define SRAM_RIGHT  ((paddr_t)CONFIG_SOC_SRAM_BASE + CONFIG_SOC_SRAM_SIZE - 1)
#define PSRAM_LEFT  ((paddr_t)CONFIG_SOC_PSRAM_BASE)
#define PSRAM_RIGHT ((paddr_t)CONFIG_SOC_PSRAM_BASE + CONFIG_SOC_PSRAM_SIZE - 1)
#define SDRAM_LEFT  ((paddr_t)CONFIG_SOC_SDRAM_BASE)
#define SDRAM_RIGHT ((paddr_t)CONFIG_SOC_SDRAM_BASE + CONFIG_SOC_SDRAM_SIZE - 1)
#define RESET_VECTOR ((paddr_t)CONFIG_SOC_RESET_VECTOR)

// ----------- Memory access declarations -----------

word_t paddr_read(paddr_t addr, int len);
void   paddr_write(paddr_t addr, int len, word_t data);
word_t vaddr_ifetch(vaddr_t addr, int len);
word_t vaddr_read(vaddr_t addr, int len);
void   vaddr_write(vaddr_t addr, int len, word_t data);

bool in_flash(paddr_t addr);
bool in_sram(paddr_t addr);
bool in_psram(paddr_t addr);
bool in_sdram(paddr_t addr);

void init_mem();

#define PAGE_SHIFT 12
#define PAGE_SIZE  (1ul << PAGE_SHIFT)
#define PAGE_MASK  (PAGE_SIZE - 1)

// ----------- Ftrace -----------

#ifdef CONFIG_FTRACE
void init_ftrace(const char *img_file);
void ftrace_call(vaddr_t pc, vaddr_t target);
void ftrace_ret(vaddr_t pc);
void ftrace_dump();
#else
inline void init_ftrace(const char *) {}
inline void ftrace_call(vaddr_t, vaddr_t) {}
inline void ftrace_ret(vaddr_t) {}
inline void ftrace_dump() {}
#endif

// ----------- Disassemble -----------

bool disassemble(char *str, int size, uint64_t pc, uint8_t *code, int nbyte);
void init_disasm();
