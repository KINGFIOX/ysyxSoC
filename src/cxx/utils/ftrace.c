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
#include <errno.h> // NOLINT: for errno
#include <fcntl.h>
#include <ftrace.h>
#include <gelf.h>   // gelf 是 libelf 提供的 "通用ELF" 接口层
#include <libelf.h> // libelf 是库本身, 提供读写ELF的基础API
#include <stdio.h>
#include <string.h>
#include <unistd.h>

typedef struct {
  vaddr_t start;
  vaddr_t end;
  char name[64];
} FuncSym;

typedef struct {
  const FuncSym *sym;
  vaddr_t addr;
} CallFrame;

static FuncSym *funcs = NULL; // 不同的 function 之间, 地址是不可能 overlay 的
static size_t func_cnt = 0;
static size_t func_cap = 0;
static CallFrame call_stack[CONFIG_FTRACE_STACK_MAX];
static size_t call_depth = 0;

typedef struct {
  char type; // 'C' call, 'R' ret
  vaddr_t pc;
  vaddr_t target;
  size_t depth;
  char name[64];
} TraceEntry;

static TraceEntry log_buf[CONFIG_FTRACE_LOG_SIZE];
static size_t log_cnt = 0;

static void log_trace(char type, vaddr_t pc, vaddr_t target, size_t depth,
                      const char *name) {
  if (log_cnt >= CONFIG_FTRACE_LOG_SIZE)
    return;
  TraceEntry *e = &log_buf[log_cnt];
  e->type = type;
  e->pc = pc;
  e->target = target;
  e->depth = depth;
  snprintf(e->name, sizeof(e->name), "%s", name);
  log_cnt++;
}

/// @param need 预留空间
static void funcs_reserve(size_t need) {
  if (need <= func_cap)
    return;
  size_t new_cap = func_cap ? func_cap * 2 : 64 /*init with 64*/;
  if (new_cap < need)
    new_cap = need; // update
  funcs = realloc(funcs, new_cap * sizeof(FuncSym));
  Assert(funcs, "ftrace: no memory");
  func_cap = new_cap;
}

/// @param size 函数在内存中占用的空间大小
static void add_func(vaddr_t start, vaddr_t size, const char *name) {
  if (size == 0)
    size = 1;
  funcs_reserve(func_cnt + 1);
  funcs[func_cnt].start = start;
  funcs[func_cnt].end = start + size;
  snprintf(funcs[func_cnt].name, sizeof(funcs[func_cnt].name), "%s", name);
  func_cnt++;
}

static int cmp_func(const void *a, const void *b) {
  vaddr_t sa = ((FuncSym *)a)->start;
  vaddr_t sb = ((FuncSym *)b)->start;
  if (sa < sb)
    return -1;
  if (sa > sb)
    return 1;
  return 0;
}

// 二分查找
static const FuncSym *find_func(vaddr_t addr) {
  if (func_cnt == 0) {
    return NULL;
  }
  int l = 0, r = (int)func_cnt - 1;
  while (l <= r) {
    int m = ((r - l) >> 1) + l;
    const FuncSym *f = &funcs[m];
    if (addr < f->start)
      r = m - 1;
    else if (addr >= f->end)
      l = m + 1;
    else
      return f;
  }
  return NULL;
}

/// @ref https://ysyx.oscc.cc/slides/2306/14.html#/elf文件格式
/// @ref .symtab 和 .dynsym 是一个 section, 用于存储符号表
static void load_symtab(Elf *e, size_t stridx, Elf_Scn *scn) {
  GElf_Shdr shdr; // section header
  gelf_getshdr(scn, &shdr);
  Elf_Data *data = elf_getdata(scn, NULL); // 获取符号表
  if (data == NULL)
    return;
  size_t count = shdr.sh_size / shdr.sh_entsize;
  for (size_t i = 0; i < count; i++) { // 遍历所有的表项
    GElf_Sym sym;                      // 符号
    gelf_getsym(data, (int)i, &sym);   // 获取表项
    if (GELF_ST_TYPE(sym.st_info) != STT_FUNC)
      continue; // 只处理函数
    if (sym.st_value == 0)
      continue; // 跳过空符号
    const char *name = elf_strptr(e, stridx, sym.st_name);
    if (name == NULL || name[0] == '\0')
      continue; // 表项空的
    add_func((vaddr_t)sym.st_value, (vaddr_t)sym.st_size, name);
  }
}


void init_ftrace(const char *img_file) {
  size_t len = strlen(img_file);
  char * elf_file = strndup(img_file, len);
  if (elf_file == NULL) {
    Log("ftrace: strdup failed");
    return;
  }

  // .bin -> .elf
  elf_file[len] = '\0';
  elf_file[len - 1] = 'f';
  elf_file[len - 2] = 'l';
  elf_file[len - 3] = 'e';

  int fd = open(elf_file, O_RDONLY);
  if (fd < 0) {
    panic("ftrace: open %s failed: %s", elf_file, strerror(errno));
  }

  if (elf_version(EV_CURRENT) == EV_NONE) {
    panic("ELF library initialization failed");
  }

  Elf *e = elf_begin(fd, ELF_C_READ, NULL); // 打开 elf 文件
  if (e == NULL) {
    panic("ftrace: elf_begin failed: %s", elf_errmsg(-1));
  }

  // section
  for (Elf_Scn *scn = elf_getscn(e, 0); scn != NULL; scn = elf_nextscn(e, scn)) {
    GElf_Shdr shdr;
    gelf_getshdr(scn, &shdr); // retrieve section header
    if (shdr.sh_type == SHT_SYMTAB || shdr.sh_type == SHT_DYNSYM) {
      load_symtab(e, shdr.sh_link, scn);
    }
  }

  elf_end(e);
  close(fd);

  if (func_cnt) {
    qsort(funcs, func_cnt, sizeof(FuncSym) /*sizeof element*/, cmp_func); // 按照 vaddr_start 排序
    Log("ftrace: loaded %zu functions from %s", func_cnt, elf_file);
  } else {
    Log("ftrace: no functions found in %s", elf_file);
  }

  free(elf_file);
}

void ftrace_call(vaddr_t pc, vaddr_t target) {
  const FuncSym *callee = find_func(target);
  const char *name = callee ? callee->name : "???";

  log_trace('C', pc, target, call_depth, name);

  if (call_depth < CONFIG_FTRACE_STACK_MAX) {
    call_stack[call_depth].sym = callee;
    call_stack[call_depth].addr = target;
    call_depth++;
  }
}

void ftrace_ret(vaddr_t pc) {
  if (call_depth > 0) {
    call_depth--;
  }

  const char *name = "???";
  if (call_depth < CONFIG_FTRACE_STACK_MAX) {
    const CallFrame *f = &call_stack[call_depth];
    if (f->sym && f->sym->name[0])
      name = f->sym->name;
  }

  log_trace('R', pc, 0, call_depth, name);
}

void ftrace_dump(void) {
  if (log_cnt == 0) {
    return;
  }

  Log("Last %d ftrace entries:", CONFIG_FTRACE_LOG_SIZE);
  for (size_t idx = 0; idx < log_cnt; idx++) {
    const TraceEntry *e = &log_buf[idx];
    size_t pad = e->depth * 2; // 计算空格
    if (pad > 2 * CONFIG_FTRACE_STACK_MAX)
      pad = 2 * CONFIG_FTRACE_STACK_MAX;
    char spaces[2 * CONFIG_FTRACE_STACK_MAX + 1];
    memset(spaces, ' ', pad);
    spaces[pad] = '\0';
    if (e->type == 'C') {
      _Log(FMT_WORD ": %scall [%s@" FMT_WORD "]\n", e->pc, spaces, e->name,
           e->target);
    } else {
      _Log(FMT_WORD ": %sret  [%s]\n", e->pc, spaces, e->name);
    }
  }
}
