package ysyx.core

import chisel3._
import chisel3.util._
import chisel3.probe.{define, Probe, ProbeValue, read}

import ysyx.core.common.{HasCSRParameter, HasCoreParameter, HasRegFileParameter}
import ysyx.core.component._
import freechips.rocketchip.amba.axi4._
import freechips.rocketchip.util._
import ysyx.CPUAXI4BundleParameters
import ysyx.core.common.NPCModule

/** Debug Bundle for difftest and tracing */
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

  val icache = IO(AXI4Bundle(axiParams))
  val dcache = IO(AXI4Bundle(axiParams))
  val probe = IO(Output(Probe(new DebugBundle)))
  val interrupt = IO(Input(Bool()))
  val fence_i = IO(Output(Bool()))

  val ifu_ = Module(new IFU(axiParams))
  val cu_ = Module(new CU)

  private def pipelineConnect[T <: Data](
      prevOut: DecoupledIO[T],
      thisIn: DecoupledIO[T],
      flush: Bool
  ) {
    prevOut.ready := thisIn.ready
    thisIn.bits := RegEnable(prevOut.bits, prevOut.valid && thisIn.ready)
    thisIn.valid := Mux(flush, false.B, RegEnable(prevOut.valid, prevOut.valid && thisIn.ready))
  }

}
