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

Q            := @
Kconfig      := $(NPC_HOME)/Kconfig
rm-distclean += include/generated include/config .config .config.old
silent := -s

# 使用系统安装的 kconfig 和 fixdep 工具
CONF   := conf
MCONF  := mconf
FIXDEP := fixdep

menuconfig:
	$(Q)$(MCONF) $(Kconfig)
	$(Q)$(CONF) $(silent) --syncconfig $(Kconfig)

savedefconfig:
	$(Q)$(CONF) $(silent) --savedefconfig=configs/defconfig $(Kconfig)

%defconfig:
	$(Q)$(CONF) $(silent) --defconfig=configs/$@ $(Kconfig)
	$(Q)$(CONF) $(silent) --syncconfig $(Kconfig)

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
