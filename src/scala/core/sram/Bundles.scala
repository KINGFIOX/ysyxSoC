package ysyx.core.sram

import chisel3._

// Signal directions are from the master's point-of-view
class SRAMBundle(val params: SRAMBundleParameters) extends Bundle {
  val req = Output(Bool())
  val wen = Output(Bool())
  val size = Output(UInt(params.sizeBits.W))
  val addr = Output(UInt(params.addrBits.W))
  val wstrb = Output(UInt(params.maskBits.W))
  val wdata = Output(UInt(params.dataBits.W))
  val ack = Input(Bool())
  val done = Input(Bool())
  val rdata = Input(UInt(params.dataBits.W))
}

object SRAMBundle {
  def apply(params: SRAMBundleParameters) = new SRAMBundle(params)
}
