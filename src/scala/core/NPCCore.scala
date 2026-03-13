package ysyx.core

import chisel3._
import chisel3.util._
import chisel3.probe.{define, Probe, ProbeValue}

import ysyx.core.common.{HasCoreParameter, HasRegFileParameter}
import ysyx.core.component._
import freechips.rocketchip.amba.axi4._
import ysyx.core.common.NPCModule

class DebugBundle
    extends Bundle
    with HasCoreParameter
    with HasRegFileParameter {
  val valid = Bool()
  val pc = UInt(dataBits.W)
  val dnpc = UInt(dataBits.W)
  val inst = UInt(InstBits.W)
  val isMMIO = Bool()
  val gpr = Vec(NRReg, UInt(dataBits.W))
  val csr = new CSRUDebugBundle
}

class NPCCore(axiParams: AXI4BundleParameters) extends NPCModule {

  val icache    = IO(AXI4Bundle(axiParams))
  val dcache    = IO(AXI4Bundle(axiParams))
  val probe     = IO(Output(Probe(new DebugBundle)))
  val interrupt = IO(Input(Bool()))
  val fence_i   = IO(Output(Bool()))

  // modules
  val fe = Module(new FrontEnd)
  val be = Module(new BackEnd)
  val instQueue = Queue(fe.io.out, entries = 4, flush = Some(be.io.flush))
  be.io.in <> instQueue
  fe.io.redirect := be.io.redirect

  // bus
  fe.icache <> icache
  be.dcache <> dcache

  // int
  be.interrupt := interrupt

  // fence
  fence_i := be.fence_i

  // probe
  define(probe, be.probe)
}
