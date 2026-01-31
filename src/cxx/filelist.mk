#***************************************************************************************
# Copyright (c) 2014-2024 Zihao Yu, Nanjing University
#
# NPC is licensed under Mulan PSL v2.
# You can use this software according to the terms and conditions of the Mulan PSL v2.
# You may obtain a copy of Mulan PSL v2 at:
#          http://license.coscl.org.cn/MulanPSL2
#
# THIS SOFTWARE IS PROVIDED ON AN "AS IS" BASIS, WITHOUT WARRANTIES OF ANY KIND,
# EITHER EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO NON-INFRINGEMENT,
# MERCHANTABILITY OR FIT FOR A PARTICULAR PURPOSE.
#
# See the Mulan PSL v2 for more details.
#**************************************************************************************/

DIRS-y += src/cxx/cpu src/cxx/monitor src/cxx/utils src/cxx/memory

ifeq ($(CONFIG_WATCHPOINT),)
SRCS-BLACKLIST-y += src/cxx/monitor/sdb/watchpoint.c
endif

CXXSRC += src/cxx/npc-main.cc
CXXSRC += src/cxx/cpu/core.cc

SHARE = $(if $(CONFIG_TARGET_SHARE),1,0)
LIBS += $(if $(CONFIG_TARGET_NATIVE_ELF),-lreadline -ldl,)

# =============================== Verilator 集成 ===============================
# Verilator 生成的目录和文件 (使用 NPC_HOME 而非 BUILD_DIR, 因为此时 BUILD_DIR 尚未定义)
VERILATOR_TOP    ?= NPCSoC
VERILATOR_MDIR   := $(NPC_HOME)/build/obj-verilator
VERILATOR_MK     := $(VERILATOR_MDIR)/V$(VERILATOR_TOP).mk
VERILATOR_LIB    := $(VERILATOR_MDIR)/V$(VERILATOR_TOP)__ALL.a

# Verilator 头文件路径 (使用 verilator --getenv 确保版本一致)
INC_PATH += $(VERILATOR_MDIR)
INC_PATH += $(shell verilator --getenv VERILATOR_ROOT)/include
INC_PATH += $(shell verilator --getenv VERILATOR_ROOT)/include/vltstd

# Verilator 头文件有一些 sign-compare 警告, 禁用之
CXXFLAGS += -Wno-sign-compare

# 链接 Verilator 生成的静态库 (模型 + 运行时)
# libverilated.a 已经包含 verilated_vcd_c.o
ARCHIVES += $(VERILATOR_LIB)
ARCHIVES += $(VERILATOR_MDIR)/libverilated.a

# Verilator 需要 pthread
LIBS += -lpthread

ifdef mainargs
ASFLAGS += -DBIN_PATH=\"$(mainargs)\"
endif
.PHONY: src/am-bin.S
