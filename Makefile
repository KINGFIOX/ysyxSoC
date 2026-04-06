#***************************************************************************************
# ysyxSoC Makefile — thin wrapper around cargo + mill
#
# 三步构建流程:
#   make verilog    — Chisel → SystemVerilog
#   make verilate   — SystemVerilog → Verilator C++ 库
#   make build      — Rust + C++ FFI → 仿真可执行文件 (cargo)
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

# =============================== Step 2: make verilate ===============================
# SystemVerilog → Verilator C++ 静态库 (CMake + Ninja)

VERILATOR_TOP       := NPCSoC
VERILATOR_MDIR      := $(BUILD_DIR)/obj-verilator
VERILATOR_LIB       := $(VERILATOR_MDIR)/V$(VERILATOR_TOP)__ALL.a
VERILATOR_CMAKE_BUILD := $(VERILATOR_MDIR)/cmake-build

VERILATOR_RTL_SRCS   := $(shell find $(RTL_DIR) -name "*.sv" 2>/dev/null)
VERILATOR_PERIP_SRCS := $(shell find src/verilog -name "*.v" 2>/dev/null)

$(VERILATOR_LIB): $(VERILATOR_RTL_SRCS) $(VERILATOR_PERIP_SRCS)
	@echo "=== Verilating + Building (CMake+Ninja) ==="
	@mkdir -p $(VERILATOR_MDIR)
	@cmake -G Ninja \
		-S $(NPC_HOME)/scripts/verilator-build \
		-B $(VERILATOR_CMAKE_BUILD) \
		-DNPC_HOME=$(abspath $(NPC_HOME)) \
		-DRTL_DIR=$(abspath $(RTL_DIR)) \
		-DOUTPUT_DIR=$(abspath $(VERILATOR_MDIR))
	@cmake --build $(VERILATOR_CMAKE_BUILD)

verilate: $(VERILATOR_LIB)

# =============================== Step 3: make build ===============================
# Rust + C++ FFI → 仿真可执行文件 (cargo build)

RELEASE ?= 1
BINARY := $(if $(findstring 1,$(RELEASE)),target/release/npc,target/debug/npc)

build:
	unset CC CXX && cargo build $(if $(findstring 1,$(RELEASE)),--release,)

release: verilate
	$(MAKE) build RELEASE=1

# =============================== make all ===============================
# 完整流程: verilog → verilate → build

all: verilate build

# =============================== 运行 & 调试 ===============================

override ARGS ?= --log=$(BUILD_DIR)/npc-log.txt
IMG ?=

NPC_EXEC = $(BINARY) $(ARGS) -i $(IMG)

run: all
	RUST_LOG=info $(NPC_EXEC)

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
	@echo "ysyxSoC Makefile (cargo backend)"
	@echo ""
	@echo "三步构建:"
	@echo "  verilog            — Step 1: Chisel → SystemVerilog"
	@echo "  verilate           — Step 2: SystemVerilog → Verilator C++ 库"
	@echo "  build              — Step 3: Rust + C++ FFI → 仿真可执行文件 (cargo, debug)"
	@echo "  release            — verilate + build (release 模式，性能更优)"
	@echo "  all                — 执行 Step 1-3"
	@echo ""
	@echo "运行与调试:"
	@echo "  run IMG=<bin>      — 全流程编译 + 运行仿真"
	@echo "  gdb IMG=<bin>      — 全流程编译 + GDB 调试"
	@echo "  wave               — 查看波形"
	@echo ""
	@echo "清理:"
	@echo "  clean              — 清理构建目录"
	@echo "  distclean          — 清理所有生成文件"

.PHONY: release all
.PHONY: run gdb wave
.PHONY: dev-init bsp idea reformat checkformat
.PHONY: clean distclean clean-all help
