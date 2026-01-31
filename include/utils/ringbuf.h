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

#ifndef __UTILS_RINGBUF_H__
#define __UTILS_RINGBUF_H__

#include <stddef.h>

/*
 * 通用环形缓冲区 (Ring Buffer) 抽象
 *
 * 使用方法:
 *
 * 1. 定义环形缓冲区结构:
 *    RINGBUF_DEFINE(MyItem, 16) my_buf = RINGBUF_INIT;
 *
 * 2. 推入元素:
 *    RINGBUF_PUSH(my_buf, 16, ((MyItem){.field = value}));
 *
 * 3. 遍历元素:
 *    RINGBUF_FOREACH(my_buf, 16, idx, pos) {
 *      const MyItem *item = RINGBUF_GET(my_buf, pos);
 *      // 使用 item...
 *    }
 */

// 定义环形缓冲区类型
// item_type: 元素类型
// capacity: 缓冲区容量
#define RINGBUF_DEFINE(item_type, capacity)                                    \
  struct {                                                                     \
    item_type items[capacity];                                                 \
    size_t ptr;                                                                \
    size_t count;                                                              \
  }

// 初始化环形缓冲区
#define RINGBUF_INIT {.ptr = 0, .count = 0}

// 推入一个元素到环形缓冲区
// rb: 环形缓冲区变量
// capacity: 缓冲区容量
// item: 要推入的元素
#define RINGBUF_PUSH(rb, capacity, item)                                       \
  do {                                                                         \
    (rb).items[(rb).ptr] = (item);                                             \
    if ((rb).count < (capacity)) {                                             \
      (rb).count++;                                                            \
    }                                                                          \
    (rb).ptr = ((rb).ptr + 1) % (capacity);                                    \
  } while (0)

// 获取有效元素的起始位置
#define RINGBUF_START(rb, capacity)                                            \
  (((rb).ptr + (capacity) - (rb).count) % (capacity))

// 检查环形缓冲区是否为空
#define RINGBUF_EMPTY(rb) ((rb).count == 0)

// 获取环形缓冲区中元素的数量
#define RINGBUF_COUNT(rb) ((rb).count)

// 获取指定位置的元素指针
#define RINGBUF_GET(rb, pos) (&(rb).items[pos])

// 遍历环形缓冲区中的所有有效元素
// rb: 环形缓冲区变量
// capacity: 缓冲区容量
// idx: 循环索引变量名 (0 到 count-1)
// pos: 实际位置变量名 (在 items 数组中的索引)
//
// 使用示例:
//   RINGBUF_FOREACH(my_buf, 16, idx, pos) {
//     const MyItem *item = RINGBUF_GET(my_buf, pos);
//     printf("item %zu: value=%d\n", idx, item->value);
//   }
#define RINGBUF_FOREACH(rb, capacity, idx, pos)                                \
  for (size_t idx = 0, pos = RINGBUF_START(rb, capacity),                      \
              _ringbuf_valid = (rb).count;                                     \
       idx < _ringbuf_valid; idx++, pos = (pos + 1) % (capacity))

// 遍历时判断是否是最后一个元素 (通常用于标记当前执行位置)
#define RINGBUF_IS_LAST(rb, idx) ((idx) == (rb).count - 1)

#endif
