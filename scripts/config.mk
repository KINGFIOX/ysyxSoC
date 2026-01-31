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

COLOR_RED := $(shell echo "\033[1;31m")
COLOR_END := $(shell echo "\033[0m")

ifeq ($(wildcard .config),) # 项目目录下没有 .config
$(warning $(COLOR_RED)Warning: .config does not exist!$(COLOR_END))
$(warning $(COLOR_RED)To build the project, first run 'make menuconfig'.$(COLOR_END))
endif

# # @ 的作用: 不显示命令的输出. Q(quiet)
# test:
# 	echo "test"
# $ make test
# > echo test
# > test
# test:
# 	@echo "test"
# $ make test
# > test
Q            := @
KCONFIG_PATH := $(NPC_HOME)/tools/kconfig
FIXDEP_PATH  := $(NPC_HOME)/tools/fixdep
Kconfig      := $(NPC_HOME)/Kconfig
rm-distclean += include/generated include/config .config .config.old
silent := -s

CONF   := $(KCONFIG_PATH)/build/conf
MCONF  := $(KCONFIG_PATH)/build/mconf
FIXDEP := $(FIXDEP_PATH)/build/fixdep

# 编译 conf
$(CONF):
	$(Q)$(MAKE) $(silent) -C $(KCONFIG_PATH) NAME=conf

# 编译 mconf
$(MCONF):
	$(Q)$(MAKE) $(silent) -C $(KCONFIG_PATH) NAME=mconf

# 编译 fixdep
$(FIXDEP):
	$(Q)$(MAKE) $(silent) -C $(FIXDEP_PATH)

# mconf Kconfig 启动图形化配置界面
# conf -s --syncconfig Kconfig 生成配置文件到 include/config/auto.conf, 用于后续的编译
menuconfig: $(MCONF) $(CONF) $(FIXDEP)
	$(Q)$(MCONF) $(Kconfig)
	$(Q)$(CONF) $(silent) --syncconfig $(Kconfig)

# conf -s --savedefconfig=configs/
savedefconfig: $(CONF)
	$(Q)$< $(silent) --$@=configs/defconfig $(Kconfig)

# $< 表示第一个依赖文件, 也就是这里的 $(CONF)
# $@ 表示目标文件, 这里是 riscv32-am_defconfig (就是匹配到的 %defconfig)
%defconfig: $(CONF) $(FIXDEP)
	$(Q)$< $(silent) --defconfig=configs/$@ $(Kconfig)
	$(Q)$< $(silent) --syncconfig $(Kconfig)

.PHONY: menuconfig savedefconfig defconfig

# Help text used by make help
help:
	@echo  '  menuconfig	  - Update current config utilising a menu based program'
	@echo  '  savedefconfig   - Save current config as configs/defconfig (minimal config)'

distclean: clean
	-@rm -rf $(rm-distclean)

.PHONY: help distclean

# call_fixdep 用来封装对 fixdep 工具的调用
# $(1) 传入的第一个参数(.o), $(2) 传入的第二个参数(.d)
# fixdep 会生成 .d 文件. 用来表示依赖关系的
# 因为c语言的机制，声明与定义分离，但是一个.o其实对应的是一个.c，但是对应的.h变了的话，依然需要重新生成.o
# TODO: 还需要进一步理解细节
define call_fixdep
	@$(FIXDEP) $(1) $(2) unused > $(1).tmp
	@mv $(1).tmp $(1)
endef
