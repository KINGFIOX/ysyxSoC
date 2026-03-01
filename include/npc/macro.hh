#pragma once

#include <cstdint>
#include <cstring>

#define NPC_STR_TEMP(x) #x
#define NPC_STR(x) NPC_STR_TEMP(x)

#define STRLEN(CONST_STR) (sizeof(CONST_STR) - 1)
#define ARRLEN(arr) (int)(sizeof(arr) / sizeof(arr[0]))

#define concat_temp(x, y) x##y
#define concat(x, y) concat_temp(x, y)
#define concat3(x, y, z) concat(concat(x, y), z)
#define concat4(x, y, z, w) concat3(concat(x, y), z, w)
#define concat5(x, y, z, v, w) concat4(concat(x, y), z, v, w)

#define CHOOSE2nd(a, b, ...) b
#define MUX_WITH_COMMA(contain_comma, a, b) CHOOSE2nd(contain_comma a, b)
#define MUX_MACRO_PROPERTY(p, macro, a, b) MUX_WITH_COMMA(concat(p, macro), a, b)

#define __P_DEF_0 X,
#define __P_DEF_1 X,
#define __P_ONE_1 X,
#define __P_ZERO_0 X,

#define MUXDEF(macro, X, Y) MUX_MACRO_PROPERTY(__P_DEF_, macro, X, Y)
#define MUXNDEF(macro, X, Y) MUX_MACRO_PROPERTY(__P_DEF_, macro, Y, X)
#define MUXONE(macro, X, Y) MUX_MACRO_PROPERTY(__P_ONE_, macro, X, Y)
#define MUXZERO(macro, X, Y) MUX_MACRO_PROPERTY(__P_ZERO_, macro, X, Y)

#define ISDEF(macro) MUXDEF(macro, 1, 0)
#define ISNDEF(macro) MUXNDEF(macro, 1, 0)
#define ISONE(macro) MUXONE(macro, 1, 0)
#define ISZERO(macro) MUXZERO(macro, 1, 0)
#define isdef(macro) (std::strcmp("" #macro, "" NPC_STR(macro)) != 0)

#define __IGNORE(...)
#define __KEEP(...) __VA_ARGS__
#define IFDEF(macro, ...) MUXDEF(macro, __KEEP, __IGNORE)(__VA_ARGS__)
#define IFNDEF(macro, ...) MUXNDEF(macro, __KEEP, __IGNORE)(__VA_ARGS__)
#define IFONE(macro, ...) MUXONE(macro, __KEEP, __IGNORE)(__VA_ARGS__)
#define IFZERO(macro, ...) MUXZERO(macro, __KEEP, __IGNORE)(__VA_ARGS__)

#define MAP(c, f) c(f)

#define BITMASK(bits) ((1ull << (bits)) - 1)
#define BITS(x, hi, lo) (((x) >> (lo)) & BITMASK((hi) - (lo) + 1))

// Sign-extend x from len bits to 64 bits. Replaces GCC statement-expression.
inline constexpr uint64_t sext(uint64_t x, int len) {
  const uint64_t mask = (1ULL << len) - 1;
  x &= mask;
  return static_cast<uint64_t>(
      static_cast<int64_t>(x << (64 - len)) >> (64 - len)); // NOLINT
}
#define SEXT(x, len) sext((x), (len))

#define ROUNDUP(a, sz) ((((uintptr_t)(a)) + (sz) - 1) & ~((sz) - 1))
#define ROUNDDOWN(a, sz) ((((uintptr_t)(a))) & ~((sz) - 1))

#define PG_ALIGN __attribute((aligned(4096)))

