# Difftest Bug 报告：NPC 与 QEMU 第一条指令不一致 （ 一次酣畅淋漓的 debug 过程 ）

## 问题描述

在使用 difftest 功能对比 NPC 和 QEMU (REF) 的执行结果时，我发现从第一条指令开始就出现寄存器不一致的错误。

### 现象

执行 `si` 单步调试时，difftest 报告 `t0` 寄存器不一致：

```
(npc) si
0x80000000: 00 00 02 97 auipc   t0, 0

+------+------------+------------+----------+
|   Difftest FAILED at PC = 0x80000000    |
+------+------------+------------+----------+
| Reg  | REF        | NPC        | Status   |
+------+------------+------------+----------+
| t0   | 0x00000000 | 0x80000000 | MISMATCH |
| pc   | 0x80000004 | 0x80000004 | OK       |
+------+------------+------------+----------+
```

- **NPC**：`t0 = 0x80000000`（执行 `auipc t0, 0` 后正确）
- **REF (QEMU)**：`t0 = 0x00000000`（错误，`auipc` 应该将 PC 值写入 t0）

后续指令的 `t0` 差异是由第一条指令的错误累积导致的。

---

## 根因分析

### 1. 初步排查

通过添加调试代码，发现：

```c
// init_difftest 之后
After init: NPC pc=0x80000000, REF pc=0x80000000  // PC 初始化正确
After init: NPC t0=0x0, REF t0=0x0                // t0 初始化正确

// difftest_step 中
Before exec: REF pc=0x80000000, t0=0x0
After exec:  REF pc=0x80000004, t0=0x0   // spike 执行后 t0 仍为 0！
NPC state:   pc=0x80000004, t0=0x80000000
```

QEMU 的 PC 从 `0x80000000` 变到 `0x80000004`（说明确实执行了一条指令），但 `t0` 却没有变化。

### 2. 深入调查

检查 QEMU 内存中的指令：

```c
[DEBUG] Memory at 0x80000000: QEMU=0x00000413, expected=0x00000413
```

- `0x00000413` = `addi s0, x0, 0`（即 `li s0, 0`）
- `0x00000297` = `auipc t0, 0`

**关键发现**：QEMU 执行的是 `addi s0, x0, 0`，而 NPC 显示执行的是 `auipc t0, 0`！

### 3. 镜像文件验证

```bash
$ xxd add-riscv32-nemu.bin | head -1
00000000: 1304 0000 ...   # 小端序读取为 0x00000413
```

用户镜像的第一条指令确实是 `0x00000413`（`addi s0, x0, 0`）。

### 4. 内置镜像检查

```c
// src/isa/riscv32/init.c
static const uint32_t img[] = {
    0x00000297, // auipc t0,0  <-- 内置镜像的第一条指令
    0x00028823, // sb  zero,16(t0)
    ...
};
```

NPC 执行的是**内置镜像**（`auipc t0, 0`），而 QEMU 执行的是**用户镜像**（`addi s0, x0, 0`）！

### 5. 根本原因：初始化顺序错误

原始的初始化顺序（`src/monitor/monitor.c`）：

```c
init_isa();           // 1. 复制内置镜像到内存
npc_core_init();      // 2. 初始化 NPC 核心（复位时取指，取到 auipc t0, 0）
load_img();           // 3. 加载用户镜像（覆盖内存，但 NPC 已经取好指令了！）
init_difftest();      // 4. 把用户镜像复制到 QEMU
```

**问题的本质**：

```
时间线：
┌─────────────────────────────────────────────────────────────────────┐
│ init_isa()      │ 内存: [auipc t0, 0] (内置镜像)                    │
├─────────────────────────────────────────────────────────────────────┤
│ npc_core_init() │ NPC 复位，取指 → IR = auipc t0, 0                 │
│                 │ （指令已经锁存到 NPC 的寄存器中！）                  │
├─────────────────────────────────────────────────────────────────────┤
│ load_img()      │ 内存: [addi s0, 0] (用户镜像覆盖)                 │
│                 │ 但 NPC 的 IR 仍然是 auipc t0, 0！                  │
├─────────────────────────────────────────────────────────────────────┤
│ init_difftest() │ QEMU 内存: [addi s0, 0] (正确的用户镜像)          │
├─────────────────────────────────────────────────────────────────────┤
│ 执行第一条指令   │ NPC 执行 auipc t0, 0 → t0 = 0x80000000           │
│                 │ QEMU 执行 addi s0, 0 → s0 = 0                     │
│                 │ → MISMATCH!                                        │
└─────────────────────────────────────────────────────────────────────┘
```

**关键点**：NPC 是一个硬件模拟器（Verilator），在 `npc_core_init()` 复位时，CPU 会进行取指操作（IF 阶段），将 PC 指向的指令从内存读取到指令寄存器（IR）中。此时内存中还是内置镜像，所以取到的是 `auipc t0, 0`。

之后 `load_img()` 虽然覆盖了内存，但 **NPC 的指令寄存器中已经锁存了旧指令**，不会因为内存变化而改变。所以执行时 NPC 用的是内置镜像的指令，而 QEMU 用的是用户镜像的指令，导致不一致。

---

## 修复方案

### 修复 1：调整初始化顺序（主要修复）

**文件**：`src/monitor/monitor.c`

```c
// 修改后的初始化顺序
init_isa();           // 1. 复制内置镜像到内存
load_img();           // 2. 加载用户镜像（覆盖内置镜像）
npc_core_init();      // 3. 初始化 NPC 核心（现在读取的是用户镜像）
init_difftest();      // 4. 把用户镜像复制到 QEMU
```

### 修复 2：修正 QEMU riscv32 的 GDB 寄存器大小

**文件**：`tools/qemu-diff/include/isa.h`

原问题：`union isa_gdb_regs` 的 `array[77]` 对于 riscv32 来说太大，导致与 QEMU 的 GDB 协议不匹配。

```c
// 修改前
struct {
    uint32_t array[77];   // 所有架构统一使用 77
};

// 修改后
struct {
#if defined(CONFIG_ISA_riscv) && !defined(CONFIG_RV64)
    uint32_t array[33];   // riscv32: 32 GPR + PC
#elif defined(CONFIG_ISA_riscv) && defined(CONFIG_RV64)
    uint64_t array[65];   // riscv64: 32 GPR + 32 FPR + PC
#endif
};
```

### 修复 3：添加 `-machine virt` 参数

**文件**：`tools/qemu-diff/include/isa.h`

```c
// 修改前
#define ISA_QEMU_ARGS "-bios", "none",

// 修改后
#define ISA_QEMU_ARGS "-machine", "virt", "-bios", "none",
```

确保 QEMU riscv32 使用正确的机器类型和内存映射。

---

## 调试过程中添加的辅助代码

为了定位问题，调试过程中添加了以下辅助代码：

### 1. 内存读取函数（保留）

**文件**：`tools/qemu-diff/src/gdb-host.c`

新增从 QEMU 读取内存的函数，用于验证内存是否正确写入：

```c
bool gdb_memcpy_from_qemu(uint32_t src, void *dest, int len) {
  char *buf = malloc(128);
  assert(buf != NULL);
  sprintf(buf, "m0x%x,%x", src, len);
  
  gdb_send(conn, (const uint8_t *)buf, strlen(buf));
  free(buf);
  
  size_t size;
  uint8_t *reply = gdb_recv(conn, &size);
  
  // 解析十六进制响应
  for (int i = 0; i < len && i * 2 < (int)size; i++) {
    uint8_t hi = reply[i * 2];
    uint8_t lo = reply[i * 2 + 1];
    hi = (hi >= 'a') ? (hi - 'a' + 10) : (hi >= 'A') ? (hi - 'A' + 10) : (hi - '0');
    lo = (lo >= 'a') ? (lo - 'a' + 10) : (lo >= 'A') ? (lo - 'A' + 10) : (lo - '0');
    ((uint8_t *)dest)[i] = (hi << 4) | lo;
  }
  
  free(reply);
  return true;
}
```

同时在 `tools/qemu-diff/src/diff-test.c` 中添加函数声明：

```c
bool gdb_memcpy_from_qemu(uint32_t, void *, int);
```

### 2. 内存验证调试代码（已清理）

**文件**：`tools/qemu-diff/src/diff-test.c`

在 `difftest_memcpy` 中添加验证，检查内存是否正确写入 QEMU：

```c
// 验证内存是否正确写入（调试用，已删除）
uint32_t verify[4];
gdb_memcpy_from_qemu(addr, verify, 16);
uint32_t *expected = (uint32_t *)buf;
fprintf(stderr, "[DEBUG] Memory at 0x%x: QEMU=0x%08x, expected=0x%08x\n", 
        addr, verify[0], expected[0]);
```

### 3. 寄存器设置验证（已清理）

**文件**：`tools/qemu-diff/src/diff-test.c`

在 `difftest_regcpy` 中添加验证，检查寄存器是否正确设置：

```c
// 验证寄存器设置（调试用，已删除）
bool ok = gdb_setregs(&qemu_r);
if (!ok) {
  fprintf(stderr, "Warning: gdb_setregs failed!\n");
}
// 验证 PC 设置是否成功
union isa_gdb_regs verify;
gdb_getregs(&verify);
if (verify.pc != qemu_r.pc) {
  fprintf(stderr, "Warning: PC set failed! expected=0x%x, actual=0x%x\n", 
          qemu_r.pc, verify.pc);
}
```

### 4. 初始化状态验证（已清理）

**文件**：`src/cpu/difftest/dut.c`

在 `init_difftest` 末尾添加验证，检查初始化后 NPC 和 REF 的状态：

```c
// 验证初始化是否成功（调试用，已删除）
CPU_state verify;
ref_difftest_regcpy(&verify, DIFFTEST_TO_DUT);
Log("After init: NPC pc=0x%x, REF pc=0x%x", cpu.pc, verify.pc);
Log("After init: NPC t0=0x%x, REF t0=0x%x", cpu.gpr[5], verify.gpr[5]);
```

### 5. 执行前后状态对比（已清理）

**文件**：`src/cpu/difftest/dut.c`

在 `difftest_step` 中添加调试代码，对比执行前后的状态变化：

```c
// 调试：执行前的状态（调试用，已删除）
CPU_state before;
ref_difftest_regcpy(&before, DIFFTEST_TO_DUT);

ref_difftest_exec(1);
ref_difftest_regcpy(&ref_r, DIFFTEST_TO_DUT);

// 调试：打印执行前后的 PC 变化
static int debug_count = 0;
if (debug_count < 3) {
  printf("[DEBUG] Before exec: REF pc=0x%x, t0=0x%x\n", before.pc, before.gpr[5]);
  printf("[DEBUG] After exec:  REF pc=0x%x, t0=0x%x\n", ref_r.pc, ref_r.gpr[5]);
  printf("[DEBUG] NPC state:   pc=0x%x, t0=0x%x\n", cpu.pc, cpu.gpr[5]);
  debug_count++;
}
```

### 辅助代码状态总结

| 代码 | 文件 | 状态 |
|------|------|------|
| `gdb_memcpy_from_qemu()` | `tools/qemu-diff/src/gdb-host.c` | ✅ 保留（可用于未来调试） |
| 函数声明 | `tools/qemu-diff/src/diff-test.c` | ✅ 保留 |
| 内存验证 `[DEBUG]` | `tools/qemu-diff/src/diff-test.c` | ❌ 已清理 |
| 寄存器验证 | `tools/qemu-diff/src/diff-test.c` | ❌ 已清理 |
| 初始化验证 | `src/cpu/difftest/dut.c` | ❌ 已清理 |
| 执行状态对比 | `src/cpu/difftest/dut.c` | ❌ 已清理 |

---

## 验证结果

修复后，difftest 正常工作：

```
$ echo -e "si\nsi\nsi\nsi\nsi\nq" | make run IMG=add-riscv32-nemu.bin

0x80000000: 00 00 04 13 mv      s0, zero    # 正确执行用户镜像
0x80000004: 00 00 91 17 auipc   sp, 9
0x80000008: ff c1 01 13 addi    sp, sp, -4
0x8000000c: 0f 40 00 ef jal     0xf4
0x80000100: ff 01 01 13 addi    sp, sp, -0x10
```

没有 MISMATCH 错误，NPC 和 QEMU 执行结果完全一致。

---

## 经验总结

1. **初始化顺序很重要**：当多个组件有依赖关系时，必须仔细考虑初始化顺序。在这个案例中，NPC 核心需要从内存读取指令，因此必须在用户镜像加载之后才能初始化。

2. **Difftest 的调试方法**：
   - 先验证初始化是否正确（PC、寄存器）
   - 检查执行前后的状态变化
   - 验证内存中的指令是否与预期一致
   - 对比两边执行的实际指令

3. **GDB 协议的架构差异**：不同 ISA 的 GDB 协议返回的寄存器数量不同，必须使用正确的数据结构大小。

---

## 修改的文件列表

| 文件 | 修改内容 |
|------|----------|
| `src/monitor/monitor.c` | 调整初始化顺序，先 `load_img()` 后 `npc_core_init()` |
| `tools/qemu-diff/include/isa.h` | 修正 `isa_gdb_regs.array` 大小；添加 `-machine virt` 参数 |
| `tools/qemu-diff/src/gdb-host.c` | 添加 `gdb_memcpy_from_qemu()` 辅助调试函数 |

---