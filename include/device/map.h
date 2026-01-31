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

#ifndef __DEVICE_MAP_H__
#define __DEVICE_MAP_H__

#include <cpu/difftest.h>

typedef void (*io_callback_t)(uint32_t, int, bool);
uint8_t *new_space(int size);

typedef struct {
  const char *name;
  // we treat ioaddr_t as paddr_t here
  paddr_t low;
  paddr_t high;
  void *space; // space 是宿主机的地址, 不是npc内的地址. npc 中写入某个地址,
               // 终归是要写入宿主机的内存的
  io_callback_t callback;
} IOMap;

#define NR_MAP 16

extern IOMap maps[NR_MAP];
extern int nr_map;

static inline bool map_inside(IOMap *map, paddr_t addr) {
  return (addr >= map->low && addr <= map->high);
}

// 找到 addr 对应的 maps 的索引
static inline int find_mapid_by_addr(paddr_t addr) {
  for (int i = 0; i < nr_map; i++) {
    if (map_inside(&maps[i], addr)) {
      difftest_skip_ref();
      return i;
    }
  }
  return -1;
}

void add_mmio_map(const char *name, paddr_t addr, void *space, uint32_t len, io_callback_t callback);

word_t map_read(paddr_t addr, int len, IOMap *map);
void map_write(paddr_t addr, int len, word_t data, IOMap *map);

#ifdef CONFIG_DTRACE
void dtrace_dump(void);
#else
static inline void dtrace_dump(void) {}
#endif

#endif
