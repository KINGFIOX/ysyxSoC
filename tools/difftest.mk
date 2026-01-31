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

ifdef CONFIG_DIFFTEST

# DIFF_REF_SO = $(NEMU_HOME)/build/$(GUEST_ISA)-nemu-interpreter-so
DIFF_REF_SO = $(NEMU_HOME)/tools/spike-diff/build/riscv32-spike-so
ARGS_DIFF = --diff=$(DIFF_REF_SO)

.PHONY: $(DIFF_REF_SO)

endif
