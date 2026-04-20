cmake_minimum_required(VERSION 3.27)
project(npc CXX C)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_EXPORT_COMPILE_COMMANDS ON)

# ========================== Environment paths ==========================

foreach(VAR NPC_HOME)
  if(NOT DEFINED ${VAR} AND DEFINED ENV{${VAR}})
    set(${VAR} "$ENV{${VAR}}")
  endif()
  if(NOT DEFINED ${VAR})
    message(FATAL_ERROR "${VAR} must be set (env or -D)")
  endif()
endforeach()

set(RTL_DIR "${NPC_HOME}/build/rtl" CACHE PATH "Directory containing generated RTL")
set(VERILATOR_MDIR "${NPC_HOME}/build/obj-verilator")
set(SRC "${NPC_HOME}/src/cpp")

# ========================== Verilator model ==========================

# CMake >= 4.0 no longer auto-propagates compilers to try_compile() projects.
# Verilator's config needs them for compiler-flag checks.
list(APPEND CMAKE_TRY_COMPILE_PLATFORM_VARIABLES
  CMAKE_CXX_COMPILER CMAKE_C_COMPILER)

find_package(verilator REQUIRED HINTS $ENV{VERILATOR_ROOT})

set(TOP_MODULE "NPCSoC" CACHE STRING "Verilator top module name")

file(GLOB_RECURSE RTL_SRCS CONFIGURE_DEPENDS "${RTL_DIR}/*.sv")
file(GLOB_RECURSE PERIP_SRCS CONFIGURE_DEPENDS "${NPC_HOME}/src/verilog/*.v")

add_library(verilator_model STATIC)

verilate(verilator_model
  SOURCES ${RTL_SRCS} ${PERIP_SRCS}
  TOP_MODULE ${TOP_MODULE}
  PREFIX V${TOP_MODULE}
  TRACE_VCD
  DIRECTORY "${VERILATOR_MDIR}"
  VERILATOR_ARGS
    --no-timing
    --timescale "1ns/1ns"
    -O2 -Wall -Wno-fatal
    "-I${NPC_HOME}/src/verilog/spi/rtl"
    "-I${RTL_DIR}/verification"
    "-I${RTL_DIR}/verification/assert"
    "-I${RTL_DIR}/verification/assume"
    "-I${RTL_DIR}/verification/cover"
  OPT_FAST -O2
  THREADS 6
  OPT_SLOW -O2
  OPT_GLOBAL -O2
)

target_compile_definitions(verilator_model PRIVATE VL_USER_STOP)

# ========================== Flex / Bison ==========================

find_package(FLEX REQUIRED)
find_package(BISON REQUIRED)

set(EXPR_GEN_DIR "${CMAKE_CURRENT_BINARY_DIR}/sdb")
file(MAKE_DIRECTORY ${EXPR_GEN_DIR})

bison_target(ExprParser "${SRC}/sdb/expr.y" "${EXPR_GEN_DIR}/expr.tab.cc"
  DEFINES_FILE "${EXPR_GEN_DIR}/expr.tab.h"
)
flex_target(ExprLexer "${SRC}/sdb/expr.l" "${EXPR_GEN_DIR}/expr.lex.cc")
add_flex_bison_dependency(ExprLexer ExprParser)

# ========================== NPC executable ==========================

add_executable(npc
  ${SRC}/main.cc
  ${SRC}/cpu/cpu_reg_view.cc
  ${SRC}/cpu/verilator_cpu.cc
  ${SRC}/cpu/spike_cpu.cc
  ${SRC}/dpi/memory.cc
  ${SRC}/dpi/dpi.cc
  ${SRC}/dpi/sync_disk.cc
  ${SRC}/dpi/uart_ns16550.cc
  ${SRC}/perf/perf_counters.cc
  ${SRC}/sdb/sdb.cc
  ${SRC}/sdb/command.cc
  ${SRC}/sdb/watchpoint.cc
  ${SRC}/sdb/scoreboard.cc
  ${SRC}/sdb/expr_eval.cc
  ${SRC}/tracer/ftrace.cc
  ${SRC}/nvboard/nvboard_bind.cc
  ${BISON_ExprParser_OUTPUTS}
  ${FLEX_ExprLexer_OUTPUTS}
)

target_include_directories(npc PRIVATE
  "${SRC}"
  "${EXPR_GEN_DIR}"
  "${VERILATOR_MDIR}"
)

option(NPC_NODIFF     "Disable difftest (skip scoreboard comparison)" OFF)
option(NPC_ITRACE     "Enable instruction trace ring buffer"          ON)
option(NPC_DTRACE     "Enable device/MMIO trace ring buffer"          ON)
option(NPC_MTRACE     "Enable memory trace ring buffer"               ON)
option(NPC_FTRACE     "Enable function call trace"                    ON)
option(NPC_WATCHPOINT "Enable watchpoint support in SDB"              ON)
option(NPC_BREAKPOINT "Enable breakpoint support in SDB"              ON)

target_compile_options(npc PRIVATE -Wall -Wextra -Wno-unused-parameter)
target_compile_definitions(npc PRIVATE VL_USER_STOP)
if(NPC_NODIFF)
  target_compile_definitions(npc PRIVATE NPC_NODIFF)
endif()
foreach(_feat ITRACE DTRACE MTRACE FTRACE WATCHPOINT BREAKPOINT)
  if(NPC_${_feat})
    target_compile_definitions(npc PRIVATE NPC_${_feat})
  endif()
endforeach()

# ========================== Link verilator model ==========================

target_link_libraries(npc PRIVATE verilator_model)
target_link_libraries(npc PRIVATE z)

# ========================== Spike (built from third_party) ==========================

add_subdirectory(third_party/spike EXCLUDE_FROM_ALL)
target_link_libraries(npc PRIVATE riscv)

# ========================== NVBoard + SDL2 ==========================

find_package(nvboard REQUIRED)
target_link_libraries(npc PRIVATE nvboard::nvboard)

# ========================== absl ==========================

find_package(absl REQUIRED)
get_filename_component(ABSL_CMAKE_DIR "${absl_DIR}" DIRECTORY)      # .../lib/cmake
get_filename_component(ABSL_LIB_DIR "${ABSL_CMAKE_DIR}" DIRECTORY)  # .../lib
get_filename_component(ABSL_ROOT_DIR "${ABSL_LIB_DIR}" DIRECTORY)   # prefix
set(ABSL_INCLUDE_DIR "${ABSL_ROOT_DIR}/include") # just for clangd
target_link_libraries(npc PRIVATE
  absl::status
  absl::statusor
  absl::strings
  absl::str_format
  absl::flags
  absl::flags_parse
  absl::flat_hash_map
  absl::log
  absl::log_globals
  absl::log_initialize
  absl::span
)
target_compile_options(npc PRIVATE "-isystem${ABSL_INCLUDE_DIR}")

# ========================== ELFIO (header-only) ==========================

find_path(ELFIO_INCLUDE_DIR elfio/elfio.hpp REQUIRED)
target_include_directories(npc PRIVATE ${ELFIO_INCLUDE_DIR})

# ========================== readline ==========================

find_library(READLINE_LIB readline REQUIRED)
target_link_libraries(npc PRIVATE ${READLINE_LIB})

# ========================== System ==========================

if(APPLE)
  target_link_libraries(npc PRIVATE c++)
else()
  target_link_libraries(npc PRIVATE stdc++)
endif()
target_link_libraries(npc PRIVATE pthread)

# ========================== Install ==========================

install(TARGETS npc RUNTIME DESTINATION bin)
