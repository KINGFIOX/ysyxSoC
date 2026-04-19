#***************************************************************************************
# ysyxSoC Makefile — thin wrapper around CMake + mill
#
# 两步构建流程:
#   make verilog    — Chisel → SystemVerilog
#   make build      — Verilator model + C++20 仿真可执行文件 (CMake + Ninja)
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
	-DRTL_DIR=$(abspath $(RTL_DIR))

# =============================== Step 2: make build ===============================
# Verilator model + C++20 仿真可执行文件

build:
	@cmake $(CMAKE_ARGS)
	@cmake --build $(NPC_BUILD_DIR)

release:
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
	@surfer $(BUILD_DIR)/npc_core.vcd

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
	@echo "构建:"
	@echo "  verilog            — Chisel → SystemVerilog"
	@echo "  build              — Verilator model + C++20 → 仿真可执行文件"
	@echo "  release            — build (release 模式，性能更优)"
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

.PHONY: verilog release all
.PHONY: run gdb wave build
.PHONY: dev-init bsp idea reformat checkformat
.PHONY: clean distclean clean-all help
