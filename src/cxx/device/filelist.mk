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

DIRS-y += src/cxx/device/io
SRCS-$(CONFIG_DEVICE) += src/cxx/device/device.c src/cxx/device/alarm.c src/cxx/device/intr.c
SRCS-$(CONFIG_HAS_SERIAL) += src/cxx/device/serial.c
SRCS-$(CONFIG_HAS_TIMER) += src/cxx/device/timer.c
SRCS-$(CONFIG_HAS_KEYBOARD) += src/cxx/device/keyboard.c
SRCS-$(CONFIG_HAS_VGA) += src/cxx/device/vga.c
SRCS-$(CONFIG_HAS_AUDIO) += src/cxx/device/audio.c
SRCS-$(CONFIG_HAS_DISK) += src/cxx/device/disk.c
SRCS-$(CONFIG_HAS_SDCARD) += src/cxx/device/sdcard.c

ifdef CONFIG_DEVICE
LIBS += $(shell sdl2-config --libs)
endif
