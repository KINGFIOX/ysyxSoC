# spike-diff

Difftest 参考实现，将 spike 封装为 `.so` 供 NPC 动态加载。

## 依赖：spike (riscv-isa-sim)

spike-diff 依赖已用 **meson** 构建的 spike。请先构建 spike：

```bash
cd $YSYX_HOME/tools/spike
meson setup build -Ddefault_isa=RV32IMAFDC
ninja -C build
```

- `-Ddefault_isa=RV32IMAFDC` 为 NPC (riscv32) 所需，与 difftest 使用的 ISA 一致
- 若使用其他构建目录（如 `build`），需在 NPC 的 meson 配置中设置 `-Dspike_build_dir=build`

## 配置选项

在 NPC 根目录执行 `meson configure build/meson` 可调整：

- `spike_path`: spike 源码路径（默认 `../tools/spike`）
- `spike_build_dir`: spike meson 构建目录名（默认 `build`）
