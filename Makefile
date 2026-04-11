#***************************************************************************************
# ysyxSoC Makefile — thin wrapper around CMake + mill
#
# 三步构建流程:
#   make verilog    — Chisel → SystemVerilog
#   make verilate   — SystemVerilog → Verilator C++ 库 (via CMake)
#   make build      — 仿真可执行文件 (verilator model + C++20, CMake + Ninja)
#
# 也可以一步到位:
#   make run IMG=<bin>
#***************************************************************************************

# =============================== 路径定义 ===============================

NPC_HOME     ?= $(abspath .)
BUILD_DIR    := $(NPC_HOME)/build
MILL         := mill

# =============================== Step 1: make verilog ===============================
# Chisel → SystemVerilog (mill)

SCALA_FILES := $(shell find src/scala -name "*.scala" ! -name "SoCConfig.scala" 2>/dev/null)

RTL_DIR    := $(BUILD_DIR)/rtl
V_SIM      := $(RTL_DIR)/NPCSoC.sv

$(V_SIM): $(SCALA_FILES)
	@mkdir -p $(RTL_DIR)
	$(MILL) -i ysyxsoc.runMain ysyx.sim.ElaborateNPCSoC --target-dir $(RTL_DIR)

verilog: $(V_SIM)

# =============================== CMake 公共参数 ===============================

RELEASE ?= 1
CMAKE_BUILD_TYPE := $(if $(findstring 1,$(RELEASE)),Release,Debug)
NPC_BUILD_DIR    := $(BUILD_DIR)/npc-build
BINARY           := $(NPC_BUILD_DIR)/npc

CMAKE_ARGS = \
	-G Ninja \
	-S $(NPC_HOME) \
	-B $(NPC_BUILD_DIR) \
	-DCMAKE_BUILD_TYPE=$(CMAKE_BUILD_TYPE) \
	-DNPC_HOME=$(NPC_HOME) \
	-DRTL_DIR=$(abspath $(RTL_DIR)) \
	-DSPIKE_HOME=$$SPIKE_HOME \
	-DNVBOARD_HOME=$$NVBOARD_HOME

# =============================== Step 2: make verilate ===============================
# 仅构建 Verilator model（不编译 NPC 可执行文件）

verilate:
	@cmake $(CMAKE_ARGS)
	@cmake --build $(NPC_BUILD_DIR) --target verilator_model

# =============================== Step 3: make build ===============================
# 完整构建：verilator model + C++20 仿真可执行文件

build:
	@cmake $(CMAKE_ARGS)
	@cmake --build $(NPC_BUILD_DIR)

release: verilate
	$(MAKE) build RELEASE=1

# =============================== make all ===============================
# 完整流程: verilog → build

all: verilog build

# =============================== 运行 & 调试 ===============================

override ARGS ?= --log=$(BUILD_DIR)/npc-log.txt
IMG ?=

NPC_EXEC = $(BINARY) $(ARGS) --image=$(IMG)

run:
	$(NPC_EXEC)

gdb: all
	gdb -s $(BINARY) --args $(NPC_EXEC)

# =============================== 开发工具 ===============================

wave:
	@gtkwave $(BUILD_DIR)/npc_core.fst

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

# =============================== 清理 ===============================

clean:
	-rm -rf $(BUILD_DIR)

distclean: clean

clean-all: distclean

# =============================== 帮助 ===============================

help:
	@echo "ysyxSoC Makefile (CMake backend)"
	@echo ""
	@echo "三步构建:"
	@echo "  verilog            — Step 1: Chisel → SystemVerilog"
	@echo "  verilate           — Step 2: 仅构建 Verilator C++ 模型"
	@echo "  build              — Step 3: verilator model + C++20 → 仿真可执行文件"
	@echo "  release            — verilate + build (release 模式，性能更优)"
	@echo "  all                — 执行 verilog + build"
	@echo ""
	@echo "运行与调试:"
	@echo "  run IMG=<bin>      — 运行仿真"
	@echo "  gdb IMG=<bin>      — GDB 调试"
	@echo "  wave               — 查看波形"
	@echo ""
	@echo "清理:"
	@echo "  clean              — 清理构建目录"
	@echo "  distclean          — 清理所有生成文件"

.PHONY: verilog verilate release all
.PHONY: run gdb wave build
.PHONY: dev-init bsp idea reformat checkformat
.PHONY: clean distclean clean-all help
