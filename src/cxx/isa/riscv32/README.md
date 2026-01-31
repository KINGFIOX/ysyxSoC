# README

## 一条指令执行的流程

```c
main at src/npc-main.c:32
    cpu_exec at src/cpu/cpu-exec.c:210
        execute at src/cpu/cpu-exec.c:155
            exec_once at src/cpu/cpu-exec.c:132
                isa_exec_once at src/isa/riscv32/inst.c:154
                    decode_exec at src/isa/riscv32/inst.c:59
```
