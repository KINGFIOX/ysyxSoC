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

#include <device/map.h>
#include <isa.h>
#include <memory/paddr.h>
#ifdef CONFIG_DTRACE
#include <utils/ringbuf.h>
#endif

// mmio
IOMap maps[NR_MAP] = {};
int nr_map = 0;

//
static IOMap *fetch_mmio_map(paddr_t addr) {
  int mapid = find_mapid_by_addr(addr);
  return (mapid == -1 ? NULL : &maps[mapid]);
}

// 重映射了, 直接 panic
static void report_mmio_overlap(const char *name1, paddr_t l1, paddr_t r1,
                                const char *name2, paddr_t l2, paddr_t r2) {
  panic("MMIO region %s@[" FMT_PADDR ", " FMT_PADDR "] is overlapped "
        "with %s@[" FMT_PADDR ", " FMT_PADDR "]",
        name1, l1, r1, name2, l2, r2);
}

/* device interface */
void add_mmio_map(const char *name, paddr_t addr, void *space, uint32_t len,
                  io_callback_t callback) {
  assert(nr_map < NR_MAP);                     // 表有限
  paddr_t left = addr, right = addr + len - 1; // 左闭 右闭

  if (in_pmem(left) || in_pmem(right)) { // 不该碰物理内存的区域
    report_mmio_overlap(name, left, right, "pmem", PMEM_LEFT, PMEM_RIGHT);
  }
  for (int i = 0; i < nr_map; i++) { // 不该重映射了
    if (left <= maps[i].high &&
        right >= maps[i].low) { // maps[i].low <= left <= right <= maps[i].high
      report_mmio_overlap(name, left, right, maps[i].name, maps[i].low,
                          maps[i].high);
    }
  }

  // 添加 map
  maps[nr_map] = (IOMap){.name = name,
                         .low = addr,
                         .high = addr + len - 1,
                         .space = space,
                         .callback = callback};
  Log("Add mmio map '%s' at [" FMT_PADDR ", " FMT_PADDR "]", maps[nr_map].name,
      maps[nr_map].low, maps[nr_map].high);
  nr_map++;
}

#if CONFIG_DTRACE

#define DTRACE_BUF_SIZE 16

#define LogDev(format, ...)                                                    \
  _Log(ANSI_FMT(format, ANSI_FG_BLUE) "\n", ##__VA_ARGS__)

typedef struct {
  const IOMap *map;
  word_t data;
  int len;
  char type; // 'I' fetch, 'R' read, 'W' write
  word_t pc;
} DtraceItem;

static RINGBUF_DEFINE(DtraceItem, DTRACE_BUF_SIZE) dtrace_buf = RINGBUF_INIT;

static void dtrace_push(const IOMap *map, word_t data, int len, char type,
                        word_t pc) {
  RINGBUF_PUSH(dtrace_buf, DTRACE_BUF_SIZE,
      ((DtraceItem){.map = map, .data = data, .len = len, .type = type, .pc = pc}));
}

void dtrace_dump(void) {
  if (RINGBUF_EMPTY(dtrace_buf)) {
    return;
  }

  LogDev("Last %d device accesses:", DTRACE_BUF_SIZE);
  RINGBUF_FOREACH(dtrace_buf, DTRACE_BUF_SIZE, idx, pos) {
    const DtraceItem *it = RINGBUF_GET(dtrace_buf, pos);
    switch (it->len) {
    case 1:
      LogDev("    %c pc=" FMT_WORD " device=%s"
             " data=0x%02x",
             it->type, it->pc, it->map->name, (uint8_t)it->data);
      break;
    case 2:
      LogDev("    %c pc=" FMT_WORD " device=%s"
             " data=0x%04x",
             it->type, it->pc, it->map->name, (uint16_t)it->data);
      break;
    case 4:
      LogDev("    %c pc=" FMT_WORD " device=%s"
             " data=0x%08x",
             it->type, it->pc, it->map->name, (uint32_t)it->data);
      break;
    default:
      Assert(false, "Invalid length: %d", it->len);
      break;
    }
  }
}
#endif

/* bus interface */
word_t mmio_read(paddr_t addr, int len) {
  IOMap *map = fetch_mmio_map(addr);

  word_t data = map_read(addr, len, map);

#ifdef CONFIG_DTRACE
  dtrace_push(map, data, len, 'R', cpu.pc);
#endif

  return data;
}

void mmio_write(paddr_t addr, int len, word_t data) {
  IOMap *map = fetch_mmio_map(addr);

#ifdef CONFIG_DTRACE
  dtrace_push(map, data, len, 'W', cpu.pc);
#endif

  map_write(addr, len, data, map);
}
