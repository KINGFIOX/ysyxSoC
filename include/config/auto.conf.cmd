deps_config := \
	src/device/Kconfig \
	src/memory/Kconfig \
	src/isa/riscv32/Kconfig \
	/home/wangfiox/Documents/ysyx-workbench/npc/Kconfig

include/config/auto.conf: \
	$(deps_config)


$(deps_config): ;
