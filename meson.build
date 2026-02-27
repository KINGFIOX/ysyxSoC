project('npc', 'cpp',
  version: '0.1.0',
  default_options: [
    'cpp_std=c++20',
    'warning_level=1',
    'werror=true',
    'optimization=2',
    'b_pie=false',
  ],
)

cxx = meson.get_compiler('cpp')

guest_isa = get_option('guest_isa')

# ======================== Configuration header ========================
# Generates builddir/include/generated/autoconf.h so that
# #include <generated/autoconf.h> resolves via the build-tree mirror.

conf = configuration_data()

# ISA (riscv32 only)
conf.set_quoted('CONFIG_ISA', guest_isa)
conf.set('CONFIG_ISA_riscv', 1)

# Boolean features
_bool_opts = {
  'trace':           'CONFIG_TRACE',
  'itrace':          'CONFIG_ITRACE',
  'ftrace':          'CONFIG_FTRACE',
  'mtrace':          'CONFIG_MTRACE',
  'etrace':          'CONFIG_ETRACE',
  'verilator_trace': 'CONFIG_VERILATOR_TRACE',
  'watchpoint':      'CONFIG_WATCHPOINT',
  'difftest':        'CONFIG_DIFFTEST',
  'rt_check':        'CONFIG_RT_CHECK',
}
foreach opt_name, def_name : _bool_opts
  if get_option(opt_name)
    conf.set(def_name, 1)
  endif
endforeach

conf.set('CONFIG_TARGET_NATIVE_ELF', 1)
conf.set('CONFIG_CC_GCC', 1)
conf.set_quoted('CONFIG_CC', 'gcc')

# Integer options
conf.set('CONFIG_IRINGBUF_SIZE',    get_option('iringbuf_size'))
conf.set('CONFIG_FTRACE_STACK_MAX', get_option('ftrace_stack_max'))
conf.set('CONFIG_FTRACE_LOG_SIZE',  get_option('ftrace_log_size'))
conf.set('CONFIG_ETRACE_BUF_SIZE',  get_option('etrace_buf_size'))
conf.set('CONFIG_TRACE_START',      get_option('trace_start'))
conf.set('CONFIG_TRACE_END',        get_option('trace_end'))

# String options
conf.set_quoted('CONFIG_ITRACE_COND', get_option('itrace_cond'))
conf.set_quoted('CONFIG_MTRACE_COND', get_option('mtrace_cond'))
conf.set_quoted('CONFIG_CC_OPT',      '-O2')

if get_option('difftest')
  conf.set_quoted('CONFIG_DIFFTEST_REF_PATH', get_option('difftest_ref_path'))
  conf.set_quoted('CONFIG_DIFFTEST_REF_NAME', get_option('difftest_ref_name'))
endif

# SoC address map — hex string values emitted as bare integer macros
_soc_map = {
  'soc_reset_vector':  'CONFIG_SOC_RESET_VECTOR',
  'soc_uart_base':     'CONFIG_SOC_UART_BASE',
  'soc_uart_size':     'CONFIG_SOC_UART_SIZE',
  'soc_gpio_base':     'CONFIG_SOC_GPIO_BASE',
  'soc_gpio_size':     'CONFIG_SOC_GPIO_SIZE',
  'soc_keyboard_base': 'CONFIG_SOC_KEYBOARD_BASE',
  'soc_keyboard_size': 'CONFIG_SOC_KEYBOARD_SIZE',
  'soc_vga_base':      'CONFIG_SOC_VGA_BASE',
  'soc_vga_size':      'CONFIG_SOC_VGA_SIZE',
  'soc_spi_ctrl_base': 'CONFIG_SOC_SPI_CTRL_BASE',
  'soc_spi_ctrl_size': 'CONFIG_SOC_SPI_CTRL_SIZE',
  'soc_flash_base':    'CONFIG_SOC_XIP_FLASH_BASE',
  'soc_flash_size':    'CONFIG_SOC_XIP_FLASH_SIZE',
  'soc_psram_base':    'CONFIG_SOC_PSRAM_BASE',
  'soc_psram_size':    'CONFIG_SOC_PSRAM_SIZE',
  'soc_mrom_base':     'CONFIG_SOC_MROM_BASE',
  'soc_mrom_size':     'CONFIG_SOC_MROM_SIZE',
  'soc_sram_base':     'CONFIG_SOC_SRAM_BASE',
  'soc_sram_size':     'CONFIG_SOC_SRAM_SIZE',
  'soc_sdram_base':    'CONFIG_SOC_SDRAM_BASE',
  'soc_sdram_size':    'CONFIG_SOC_SDRAM_SIZE',
}
foreach opt_name, def_name : _soc_map
  conf.set(def_name, get_option(opt_name))
endforeach

# Generate include/generated/autoconf.h inside the build tree.
# include_directories() adds BOTH source and build counterparts,
# so #include <generated/autoconf.h> just works.
subdir('include/generated')

# Generate SoCConfig.scala for Chisel from the same option values.
# Output lands in builddir; the Makefile copies it to src/scala/ before mill.
soc_config_scala = configure_file(
  input: 'src/scala/SoCConfig.scala.in',
  output: 'SoCConfig.scala',
  configuration: conf,
)

# ======================== Dependencies ========================

dep_readline = cxx.find_library('readline')
dep_dl       = cxx.find_library('dl')
dep_pthread  = dependency('threads')

dep_capstone = dependency('capstone', required: get_option('itrace'))
dep_elf      = cxx.find_library('elf', required: get_option('ftrace'))

# ======================== Verilator integration ========================

verilator_root = run_command('verilator', '--getenv', 'VERILATOR_ROOT',
  check: true,
).stdout().strip()

verilator_mdir = meson.project_source_root() / 'build' / 'obj-verilator'

verilator_inc_args = [
  '-I' + verilator_mdir,
  '-I' + verilator_root / 'include',
  '-I' + verilator_root / 'include' / 'vltstd',
]

verilator_link_args = [
  verilator_mdir / 'VNPCSoC__ALL.a',
  verilator_mdir / 'libverilated.a',
]

# ======================== Include directories ========================

inc_dirs = include_directories(
  'include',
  'src/cxx/engine/verilator',
  'src/cxx/monitor/sdb',
)

# ======================== Flex++ / Bison ========================

flex_prog  = find_program('flex')
bison_prog = find_program('bison')

# Bison generates C code but we compile it as C++ (output .cc)
bison_gen = custom_target('expr_parser',
  input: 'src/cxx/monitor/sdb/expr.y',
  output: ['expr.tab.cc', 'expr.tab.hh'],
  command: [bison_prog, '-d', '-o', '@OUTPUT0@', '@INPUT@'],
)

# Flex++ (--c++ for C++ scanner class) generates C++ code
flex_gen = custom_target('expr_lexer',
  input: 'src/cxx/monitor/sdb/expr.l',
  output: 'expr.lex.cc',
  depends: bison_gen,
  command: [flex_prog, '-o', '@OUTPUT0@', '@INPUT@'],
)

# ======================== Source files ========================

cc_sources = files(
  'src/cxx/cpu/difftest/dut.cc',
  'src/cxx/isa/riscv32/difftest/dut.cc',
  'src/cxx/isa/riscv32/init.cc',
  'src/cxx/isa/riscv32/reg.cc',
  'src/cxx/isa/riscv32/logo.cc',
  'src/cxx/isa/riscv32/system/intr.cc',
  'src/cxx/isa/riscv32/system/mmu.cc',
  'src/cxx/npc-main.cc',
  'src/cxx/cpu/core.cc',
  'src/cxx/cpu/cpu-exec.cc',
  'src/cxx/memory/address_space.cc',
  'src/cxx/memory/flash.cc',
  'src/cxx/memory/mrom.cc',
  'src/cxx/memory/psram.cc',
  'src/cxx/memory/sdram.cc',
  'src/cxx/memory/sram.cc',
  'src/cxx/memory/vaddr.cc',
  'src/cxx/engine/verilator/dpi.cc',
  'src/cxx/engine/verilator/hostcall.cc',
  'src/cxx/engine/verilator/init.cc',
  'src/cxx/monitor/monitor.cc',
  'src/cxx/monitor/sdb/expr.cc',
  'src/cxx/monitor/sdb/sdb.cc',
  'src/cxx/utils/log.cc',
  'src/cxx/utils/state.cc',
  'src/cxx/utils/timer.cc',
)

if get_option('watchpoint')
  cc_sources += files('src/cxx/monitor/sdb/watchpoint.cc')
endif

if get_option('itrace')
  cc_sources += files('src/cxx/utils/disasm.cc')
endif

if get_option('ftrace')
  cc_sources += files('src/cxx/utils/ftrace.cc')
endif

# ======================== Compile arguments ========================

common_args = [
  '-D__GUEST_ISA__=@0@'.format(guest_isa),
  '-DITRACE_COND=@0@'.format(get_option('itrace_cond')),
] + verilator_inc_args

cxx_only_args = ['-Wno-sign-compare', '-Wno-narrowing']

# ======================== Build the simulator ========================

deps = [dep_readline, dep_dl, dep_pthread]
if get_option('itrace')
  deps += dep_capstone
endif
if get_option('ftrace')
  deps += dep_elf
endif

npc_exe = executable(guest_isa + '-npc',
  cc_sources,
  bison_gen, flex_gen,
  cpp_args: common_args + cxx_only_args,
  link_args: ['-no-pie'] + verilator_link_args,
  include_directories: [inc_dirs],
  dependencies: deps,
  install: false,
)
