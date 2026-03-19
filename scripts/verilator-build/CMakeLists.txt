cmake_minimum_required(VERSION 3.27)
project(verilator_build CXX)

foreach(VAR NPC_HOME RTL_DIR OUTPUT_DIR)
  if(NOT DEFINED ${VAR})
    message(FATAL_ERROR "${VAR} must be defined")
  endif()
endforeach()

set(TOP_MODULE "NPCSoC" CACHE STRING "Verilator top module name")

find_package(verilator REQUIRED HINTS $ENV{VERILATOR_ROOT})

file(GLOB_RECURSE RTL_SRCS CONFIGURE_DEPENDS "${RTL_DIR}/*.sv")
file(GLOB_RECURSE PERIP_SRCS CONFIGURE_DEPENDS "${NPC_HOME}/src/verilog/*.v")

add_library(model STATIC)

verilate(model
  SOURCES ${RTL_SRCS} ${PERIP_SRCS}
  TOP_MODULE ${TOP_MODULE}
  PREFIX V${TOP_MODULE}
  TRACE_FST
  DIRECTORY "${OUTPUT_DIR}"
  VERILATOR_ARGS
    --no-timing --autoflush --assert
    --timescale "1ns/1ns"
    -O2 -Wall -Wno-fatal
    "-I${NPC_HOME}/src/verilog/spi/rtl"
    "-I${NPC_HOME}/src/verilog/uart16550/rtl"
    "-I${RTL_DIR}/verification"
    "-I${RTL_DIR}/verification/assert"
    "-I${RTL_DIR}/verification/assume"
    "-I${RTL_DIR}/verification/cover"
  OPT_FAST -O2
  OPT_SLOW -O2
  OPT_GLOBAL -O2
)

target_compile_features(model PRIVATE cxx_std_17)

set_target_properties(model PROPERTIES
  OUTPUT_NAME "V${TOP_MODULE}__ALL"
  PREFIX ""
  ARCHIVE_OUTPUT_DIRECTORY "${OUTPUT_DIR}"
)
