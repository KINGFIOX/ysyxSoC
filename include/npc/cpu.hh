#pragma once

#include <npc/isa.hh>

void cpu_exec(uint64_t n);
void set_npc_state(int state, vaddr_t pc, int halt_ret);
void invalid_inst(vaddr_t thispc);

#define NPCTRAP(thispc, code) set_npc_state(NPC_END, thispc, code)
#define INV(thispc) invalid_inst(thispc)

bool npc_core_init(int argc, char *argv[]);
void npc_core_fini();

inline uint32_t inst_fetch(vaddr_t *pc, int len) {
  uint32_t inst = vaddr_ifetch(*pc, len);
  (*pc) += len;
  return inst;
}

// --- pattern matching mechanism ---

__attribute__((always_inline)) static inline void
pattern_decode(const char *str, int len, uint64_t *key, uint64_t *mask,
               uint64_t *shift) {
  uint64_t __key = 0, __mask = 0, __shift = 0;
#define macro(i)                                                               \
  if ((i) >= len)                                                              \
    goto finish;                                                               \
  else {                                                                       \
    char c = str[i];                                                           \
    if (c != ' ') {                                                            \
      Assert(c == '0' || c == '1' || c == '?',                                 \
             "invalid character '%c' in pattern string", c);                   \
      __key = (__key << 1) | (c == '1' ? 1 : 0);                               \
      __mask = (__mask << 1) | (c == '?' ? 0 : 1);                             \
      __shift = (c == '?' ? __shift + 1 : 0);                                  \
    }                                                                          \
  }

#define macro2(i)                                                              \
  macro(i);                                                                    \
  macro((i) + 1)
#define macro4(i)                                                              \
  macro2(i);                                                                   \
  macro2((i) + 2)
#define macro8(i)                                                              \
  macro4(i);                                                                   \
  macro4((i) + 4)
#define macro16(i)                                                             \
  macro8(i);                                                                   \
  macro8((i) + 8)
#define macro32(i)                                                             \
  macro16(i);                                                                  \
  macro16((i) + 16)
#define macro64(i)                                                             \
  macro32(i);                                                                  \
  macro32((i) + 32)
  macro64(0);
  panic("pattern too long");
#undef macro
finish:
  *key = __key >> __shift;
  *mask = __mask >> __shift;
  *shift = __shift;
}

__attribute__((always_inline)) static inline void
pattern_decode_hex(const char *str, int len, uint64_t *key, uint64_t *mask,
                   uint64_t *shift) {
  uint64_t __key = 0, __mask = 0, __shift = 0;
#define macro(i)                                                               \
  if ((i) >= len)                                                              \
    goto finish;                                                               \
  else {                                                                       \
    char c = str[i];                                                           \
    if (c != ' ') {                                                            \
      Assert((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || c == '?',     \
             "invalid character '%c' in pattern string", c);                   \
      __key = (__key << 4) | (c == '?'                 ? 0                     \
                              : (c >= '0' && c <= '9') ? c - '0'               \
                                                       : c - 'a' + 10);        \
      __mask = (__mask << 4) | (c == '?' ? 0 : 0xf);                           \
      __shift = (c == '?' ? __shift + 4 : 0);                                  \
    }                                                                          \
  }

  macro16(0);
  panic("pattern too long");
#undef macro
finish:
  *key = __key >> __shift;
  *mask = __mask >> __shift;
  *shift = __shift;
}

#define INSTPAT(pattern, ...)                                                  \
  do {                                                                         \
    uint64_t key, mask, shift;                                                 \
    pattern_decode(pattern, STRLEN(pattern), &key, &mask, &shift);             \
    if ((((uint64_t)INSTPAT_INST(s) >> shift) & mask) == key) {                \
      INSTPAT_MATCH(s, ##__VA_ARGS__);                                         \
      goto *(__instpat_end);                                                   \
    }                                                                          \
  } while (0)

#define INSTPAT_START(name)                                                    \
  _Pragma("GCC diagnostic push")                                               \
      _Pragma("GCC diagnostic ignored \"-Wpedantic\"") {                       \
    const void *__instpat_end = &&concat(__instpat_end_, name);
#define INSTPAT_END(name)                                                      \
  concat(__instpat_end_, name) :;                                              \
  }                                                                            \
  _Pragma("GCC diagnostic pop")
