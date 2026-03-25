package ysyx.spike

import chisel3._
import chisel3.util._

import ysyx.core.common._
import ysyx.core.backend.BackEnd
import ysyx.core.sram.SRAMBundle

class NPCCore extends NPCBundle {

  val dcache = IO(SRAMBundle(sramParams))
  val perip = IO(SRAMBundle(sramParams))
  val interrupt = IO(Input(Bool()))
  val fence_i = IO(Output(Bool()))

  // modules
  val be = Module(new BackEnd)

  // bpus
  be.dcache <> dcache
  be.perip <> perip

  // int
  be.interrupt := interrupt
  
  // fence
  fence_i := be.fence_i
  
  // probe
  val probe = IO(chiselTypeOf(be.probe))
  probe := be.probe
}
