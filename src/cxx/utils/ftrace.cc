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

#include <npc/trace_config.hh>

#include <npc/common.hh>

#include <algorithm>
#include <cerrno>
#include <cstdio>
#include <cstring>
#include <fcntl.h>
#include <gelf.h>
#include <libelf.h>
#include <unistd.h>
#include <vector>

namespace {

struct FuncSym {
  vaddr_t start;
  vaddr_t end;
  char name[64];
};

struct CallFrame {
  const FuncSym *sym;
  vaddr_t addr;
};

struct TraceEntry {
  char type; // 'C' call, 'R' ret
  vaddr_t pc;
  vaddr_t target;
  size_t depth;
  char name[64];
};

std::vector<FuncSym> g_funcs;
CallFrame g_call_stack[npc::trace::Config::ftrace_stack_max];
size_t g_call_depth = 0;

std::vector<TraceEntry> g_log;

void log_trace(char type, vaddr_t pc, vaddr_t target, size_t depth,
               const char *name) {
  if (g_log.size() >= npc::trace::Config::ftrace_log_size) return;
  TraceEntry e;
  e.type = type;
  e.pc = pc;
  e.target = target;
  e.depth = depth;
  std::snprintf(e.name, sizeof(e.name), "%s", name);
  g_log.push_back(e);
}

const FuncSym *find_func(vaddr_t addr) {
  if (g_funcs.empty()) return nullptr;
  // binary search: funcs sorted by start address
  int l = 0, r = static_cast<int>(g_funcs.size()) - 1;
  while (l <= r) {
    int m = l + (r - l) / 2;
    if (addr < g_funcs[m].start)
      r = m - 1;
    else if (addr >= g_funcs[m].end)
      l = m + 1;
    else
      return &g_funcs[m];
  }
  return nullptr;
}

void load_symtab(Elf *e, size_t stridx, Elf_Scn *scn) {
  GElf_Shdr shdr;
  gelf_getshdr(scn, &shdr);
  Elf_Data *data = elf_getdata(scn, nullptr);
  if (!data) return;
  size_t count = shdr.sh_size / shdr.sh_entsize;
  for (size_t i = 0; i < count; i++) {
    GElf_Sym sym;
    gelf_getsym(data, static_cast<int>(i), &sym);
    if (GELF_ST_TYPE(sym.st_info) != STT_FUNC) continue;
    if (sym.st_value == 0) continue;
    const char *name = elf_strptr(e, stridx, sym.st_name);
    if (!name || name[0] == '\0') continue;
    FuncSym fs;
    fs.start = static_cast<vaddr_t>(sym.st_value);
    vaddr_t sz = static_cast<vaddr_t>(sym.st_size);
    fs.end = fs.start + (sz ? sz : 1);
    std::snprintf(fs.name, sizeof(fs.name), "%s", name);
    g_funcs.push_back(fs);
  }
}

} // anonymous namespace

void init_ftrace(const char *img_file) {
  size_t len = std::strlen(img_file);
  char *elf_file = strndup(img_file, len);
  if (!elf_file) {
    Log("ftrace: strdup failed");
    return;
  }

  // .bin -> .elf
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

  Elf *e = elf_begin(fd, ELF_C_READ, nullptr);
  if (!e) {
    panic("ftrace: elf_begin failed: %s", elf_errmsg(-1));
  }

  for (Elf_Scn *scn = elf_getscn(e, 0); scn; scn = elf_nextscn(e, scn)) {
    GElf_Shdr shdr;
    gelf_getshdr(scn, &shdr);
    if (shdr.sh_type == SHT_SYMTAB || shdr.sh_type == SHT_DYNSYM) {
      load_symtab(e, shdr.sh_link, scn);
    }
  }

  elf_end(e);
  close(fd);

  if (!g_funcs.empty()) {
    std::sort(g_funcs.begin(), g_funcs.end(),
              [](const FuncSym &a, const FuncSym &b) {
                return a.start < b.start;
              });
    Log("ftrace: loaded {} functions from {}", g_funcs.size(), elf_file);
  } else {
    Log("ftrace: no functions found in {}", elf_file);
  }

  free(elf_file);
}

void ftrace_call(vaddr_t pc, vaddr_t target) {
  const FuncSym *callee = find_func(target);
  const char *name = callee ? callee->name : "???";

  log_trace('C', pc, target, g_call_depth, name);

  constexpr auto max = npc::trace::Config::ftrace_stack_max;
  if (g_call_depth < max) {
    g_call_stack[g_call_depth].sym = callee;
    g_call_stack[g_call_depth].addr = target;
    g_call_depth++;
  }
}

void ftrace_ret(vaddr_t pc) {
  if (g_call_depth > 0) g_call_depth--;

  const char *name = "???";
  constexpr auto max = npc::trace::Config::ftrace_stack_max;
  if (g_call_depth < max) {
    const CallFrame *f = &g_call_stack[g_call_depth];
    if (f->sym && f->sym->name[0]) name = f->sym->name;
  }

  log_trace('R', pc, 0, g_call_depth, name);
}

void ftrace_dump() {
  if (g_log.empty()) return;

  constexpr auto max = npc::trace::Config::ftrace_stack_max;
  Log("Last {} ftrace entries:", g_log.size());
  for (const auto &e : g_log) {
    size_t pad = e.depth * 2;
    if (pad > 2 * max) pad = 2 * max;
    char spaces[2 * max + 1];
    std::memset(spaces, ' ', pad);
    spaces[pad] = '\0';
    if (e.type == 'C') {
      _Log("{:08x}: {}call [{}@{:08x}]\n", e.pc, spaces, e.name, e.target);
    } else {
      _Log("{:08x}: {}ret  [{}]\n", e.pc, spaces, e.name);
    }
  }
}
