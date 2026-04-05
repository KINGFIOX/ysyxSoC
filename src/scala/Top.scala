package ysyx

import chisel3._
import org.chipsalliance.cde.config.{Parameters, Config}
import freechips.rocketchip.system._
import freechips.rocketchip.diplomacy.LazyModule

import ysyx.core.DebugBundle
import ysyx.soc.ysyxSoCFull
import ysyx.device.ExternalPins

class ysyxSoCTop extends Module {
  implicit val config: Parameters = new Config(
    new DefaultConfig
  )

  val io = IO(new Bundle {})
  val dut = LazyModule(new ysyxSoCFull)
  val mdut = Module(dut.module)
  mdut.dontTouchPorts() // mark all ports as don't touch
  mdut.externalPins := DontCare
}
