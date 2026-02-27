# spike-diff: difftest 参考实现，将 spike 封装为 .so 供 NPC 动态加载
# 依赖: 已用 meson 构建的 riscv-isa-sim (spike)，路径由 spike_path、spike_build_dir 选项指定

fs = import('fs')
spike_path = get_option('spike_path')
spike_build_dir = get_option('spike_build_dir')
spike_src = meson.project_source_root() / spike_path
spike_build = spike_src / spike_build_dir
spike_libriscv = spike_build / 'libriscv.so'

if not fs.exists(spike_libriscv)
  error('spike not built. Run: cd ' + spike_src + ' && meson setup ' + spike_build_dir + ' -Ddefault_isa=RV32IMAFDC && ninja -C ' + spike_build_dir)
endif

# spike 在项目外，使用 cpp_args 的 -I 传入
# libriscv.so 已 link_with fesvr/fdt/softfloat/disasm，可单独链接
spike_inc_args = [
  '-I' + spike_src,
  '-I' + (spike_src / 'fesvr'),
  '-I' + (spike_src / 'riscv'),
  '-I' + (spike_src / 'riscv_gen'),
  '-I' + (spike_src / 'disasm'),
  '-I' + (spike_src / 'customext'),
  '-I' + (spike_src / 'fdt'),
  '-I' + (spike_src / 'softfloat'),
  '-I' + (spike_src / 'spike_main'),
  '-I' + (spike_src / 'spike_dasm'),
  '-I' + spike_build,
]

# meson 版 spike: libriscv.so 已包含 fesvr/fdt/softfloat/disasm，无需 libspike_main
spike_lib_files = [spike_build / 'libriscv.so']

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
  build_rpath: spike_build,
  cpp_args: [
    '-std=c++17',
    '-O2',
    '-fPIC',
    '-fvisibility=hidden',
  ] + spike_inc_args + npc_inc_args,
  name_prefix: '',
  install: false,
)
