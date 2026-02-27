# spike-diff: difftest 参考实现，将 spike 封装为 .so 供 NPC 动态加载
# 依赖: 已构建的 riscv-isa-sim (spike)，路径由 spike_path 选项指定

fs = import('fs')
spike_path = get_option('spike_path')
spike_src = meson.project_source_root() / spike_path
spike_build = spike_src / 'build'
spike_lib_main = spike_build / 'libspike_main.a'

if not fs.exists(spike_lib_main)
  error('spike not built. Run: cd ' + spike_src + ' && mkdir -p build && cd build && ../configure && make')
endif

# spike 在项目外，使用 cpp_args 的 -I 传入
spike_inc_args = [
  '-I' + spike_src,
  '-I' + (spike_src / 'fesvr'),
  '-I' + (spike_src / 'riscv'),
  '-I' + (spike_src / 'disasm'),
  '-I' + (spike_src / 'customext'),
  '-I' + (spike_src / 'fdt'),
  '-I' + (spike_src / 'softfloat'),
  '-I' + (spike_src / 'spike_main'),
  '-I' + (spike_src / 'spike_dasm'),
  '-I' + spike_build,
]

# 必须使用 .a 静态库，.so 会导致 undefined reference（spike 的 sim_t 等未导出）
spike_lib_files = [
  spike_build / 'libspike_main.a',
  spike_build / 'libriscv.a',
  spike_build / 'libdisasm.a',
  spike_build / 'libsoftfloat.a',
  spike_build / 'libfesvr.a',
  spike_build / 'libfdt.a',
]

# NPC include: 使用相对路径，避免 absolute path 错误
npc_inc = include_directories('../../include')
# generated/autoconf.h 在 build 目录，通过 cpp_args 传入
# <generated/autoconf.h> 需要 include 的父目录
npc_inc_args = ['-I' + (meson.project_build_root() / 'include')]

guest_isa = get_option('guest_isa')
diff_name = guest_isa + '-spike-so'

spike_diff_so = shared_library(
  diff_name,
  'difftest.cc',
  include_directories: npc_inc,
  link_args: spike_lib_files,
  cpp_args: [
    '-std=c++17',
    '-O2',
    '-fPIC',
    '-fvisibility=hidden',
  ] + spike_inc_args + npc_inc_args,
  name_prefix: '',
  install: false,
)
