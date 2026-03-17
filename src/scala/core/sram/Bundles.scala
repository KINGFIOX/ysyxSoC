package ysyx.core.sram

import chisel3._

// Signal directions are from the master's point-of-view
class SRAMBundle(val params: SRAMBundleParameters) extends Bundle {
  val addr = Output(UInt(params.addrBits.W))
  val wen = Output(Bool())
  val wmask = Output(UInt(params.maskBits.W))
  val wdata = Output(UInt(params.dataBits.W))
  val ren = Output(Bool())
  val rdata = Input(UInt(params.dataBits.W))
}

object SRAMBundle {
  def apply(params: SRAMBundleParameters) = new SRAMBundle(params)
}
