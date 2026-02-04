#***************************************************************************************
# ysyxSoC Makefile
# 
# 功能:
#   1. Chisel -> Verilog 生成
#   2. Verilator 编译
#   3. 仿真程序构建
#   4. Kconfig 配置系统
#***************************************************************************************

# =============================== 路径定义 ===============================

NPC_HOME  := $(abspath .)
BUILD_DIR := $(NPC_HOME)/build
MILL      := mill

# =============================== Chisel -> Verilog ===============================

SCALA_FILES := $(shell find src/scala -name "*.scala" 2>/dev/null)

# 综合用: ysyxSoCFull.v (ysyxSoCTop 顶层, 无 step/debug 接口)
V_SYNTH_GEN   := $(BUILD_DIR)/ysyxSoCTop.sv
V_SYNTH_FINAL := $(BUILD_DIR)/ysyxSoCFull.v

# 仿真用: NPCSoC.v (NPCSoC 顶层, 暴露 step/debug 接口)
V_SIM_GEN   := $(BUILD_DIR)/NPCSoC.sv
V_SIM_FINAL := $(BUILD_DIR)/NPCSoC.v

# 生成 ysyxSoCFull.v (用于综合)
$(V_SYNTH_FINAL): $(SCALA_FILES)
	$(MILL) -i ysyxsoc.runMain ysyx.Elaborate --target-dir $(@D)
	mv $(V_SYNTH_GEN) $@
	sed -i -e 's/_\(aw\|ar\|w\|r\|b\)_\(\|bits_\)/_\1/g' $@
	sed -i '/firrtl_black_box_resource_files.f/, $$d' $@

# 生成 NPCSoC.v (用于仿真)
$(V_SIM_FINAL): $(SCALA_FILES)
	$(MILL) -i ysyxsoc.runMain ysyx.ElaborateNPCSoC --target-dir $(@D)
	mv $(V_SIM_GEN) $@
	sed -i -e 's/_\(aw\|ar\|w\|r\|b\)_\(\|bits_\)/_\1/g' $@
	sed -i '/firrtl_black_box_resource_files.f/, $$d' $@

verilog: $(V_SYNTH_FINAL)

# =============================== Verilator 编译 ===============================

VERILATOR      ?= verilator
VERILATOR_TOP  := NPCSoC
VERILATOR_MDIR := $(BUILD_DIR)/obj-verilator
VERILATOR_MK   := $(VERILATOR_MDIR)/V$(VERILATOR_TOP).mk
VERILATOR_LIB  := $(VERILATOR_MDIR)/V$(VERILATOR_TOP)__ALL.a

# Verilator 配置选项 (从 menuconfig 获取)
VERILATOR_DEFINES := $(if $(CONFIG_DIFFTEST),+define+CONFIG_DIFFTEST,)
VERILATOR_DEFINES += $(if $(CONFIG_VERILATOR_TRACE),+define+CONFIG_VERILATOR_TRACE,)

# SV 文件列表 (仿真用)
VERILATOR_SRCS := $(V_SIM_FINAL)
VERILATOR_SRCS += $(shell find $(BUILD_DIR) -maxdepth 1 -name "*.sv" 2>/dev/null)
VERILATOR_SRCS += $(shell find $(BUILD_DIR) -maxdepth 1 -name "*.v" ! -name "NPCSoC.v" ! -name "ysyxSoCFull.v" 2>/dev/null)
VERILATOR_SRCS += $(shell find perip -name "*.v" 2>/dev/null)

# Include 路径 (用于 perip 中的 `include)
VERILATOR_INCS := -I$(NPC_HOME)/perip/spi/rtl
VERILATOR_INCS += -I$(NPC_HOME)/perip/uart16550/rtl

# 生成 Verilator Makefile
$(VERILATOR_MK): $(V_SIM_FINAL)
	@echo "=== Verilating $(VERILATOR_TOP) ==="
	@mkdir -p $(VERILATOR_MDIR)
	$(VERILATOR) --cc $(VERILATOR_SRCS) \
		--Mdir $(VERILATOR_MDIR) \
		--top-module $(VERILATOR_TOP) \
		--trace --no-timing --autoflush \
		-O2 -Wall -Wno-fatal \
		$(VERILATOR_INCS) \
		$(VERILATOR_DEFINES) \
		-CFLAGS "-std=c++17 -O2"

# 编译 Verilator 库
$(VERILATOR_LIB): $(VERILATOR_MK)
	@echo "=== Building Verilator library ==="
	$(MAKE) -C $(VERILATOR_MDIR) -f V$(VERILATOR_TOP).mk

verilate: $(VERILATOR_LIB)

# =============================== 仿真程序构建 ===============================

# 构建完整的仿真程序
build: verilate
	$(MAKE) -f scripts/native.mk NPC_HOME=$(NPC_HOME)

# =============================== 开发工具 ===============================

dev-init:
	git submodule update --init --recursive
	cd rocket-chip && git apply ../patch/rocket-chip.patch

bsp:
	$(MILL) -i mill.bsp.BSP/install

idea:
	$(MILL) -i mill.idea.GenIdea/idea

reformat:
	$(MILL) -i __.reformat

checkformat:
	$(MILL) -i __.checkFormat

# =============================== Kconfig 配置 ===============================

menuconfig:
	$(MAKE) -f scripts/native.mk NPC_HOME=$(NPC_HOME) menuconfig

savedefconfig:
	$(MAKE) -f scripts/native.mk NPC_HOME=$(NPC_HOME) savedefconfig

%defconfig:
	$(MAKE) -f scripts/native.mk NPC_HOME=$(NPC_HOME) $@

# =============================== 运行与调试 ===============================

IMG ?=

run: build
	$(MAKE) -f scripts/native.mk NPC_HOME=$(NPC_HOME) run IMG=$(IMG)

gdb: build
	$(MAKE) -f scripts/native.mk NPC_HOME=$(NPC_HOME) gdb IMG=$(IMG)

sim:
	@gtkwave $(BUILD_DIR)/npc_core.vcd

# =============================== 清理 ===============================

clean:
	-rm -rf $(BUILD_DIR)

clean-tools:
	$(MAKE) -f scripts/native.mk NPC_HOME=$(NPC_HOME) clean-tools

distclean: clean
	$(MAKE) -f scripts/native.mk NPC_HOME=$(NPC_HOME) distclean

clean-all: distclean clean-tools

# =============================== 帮助 ===============================

help:
	@echo "ysyxSoC Makefile"
	@echo ""
	@echo "Chisel -> Verilog:"
	@echo "  verilog       - 生成 ysyxSoCFull.v (综合用)"
	@echo ""
	@echo "Verilator:"
	@echo "  verilate      - 编译 Verilator 库 (使用 NPCSoC)"
	@echo "  build         - 构建仿真程序"
	@echo ""
	@echo "运行与调试:"
	@echo "  run IMG=<bin> - 运行仿真"
	@echo "  gdb IMG=<bin> - GDB 调试"
	@echo "  sim           - 查看波形"
	@echo ""
	@echo "配置:"
	@echo "  menuconfig    - 图形化配置界面"
	@echo "  savedefconfig - 保存当前配置"
	@echo ""
	@echo "开发工具:"
	@echo "  dev-init      - 初始化子模块"
	@echo "  bsp           - 安装 BSP"
	@echo "  idea          - 生成 IntelliJ IDEA 项目"
	@echo ""
	@echo "清理:"
	@echo "  clean         - 清理构建目录"
	@echo "  distclean     - 清理所有生成文件"
	@echo "  clean-all     - 清理所有 (包括工具)"

.PHONY: verilog verilate build
.PHONY: dev-init bsp idea reformat checkformat
.PHONY: menuconfig savedefconfig
.PHONY: run gdb sim
.PHONY: clean clean-tools distclean clean-all
.PHONY: help
