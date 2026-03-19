package ysyx

import chisel3._
import org.chipsalliance.cde.config.{Parameters, Config}
import freechips.rocketchip.system._
import freechips.rocketchip.diplomacy.LazyModule

import ysyx.core.DebugBundle
import ysyx.soc.ysyxSoCFull
import ysyx.device.ExternalPins

object Config {
  def hasChipLink: Boolean = false
}

class ysyxSoCTop extends Module {
  implicit val config: Parameters = new Config(
    new Edge32BitConfig ++ new DefaultRV32Config
  )

  val io = IO(new Bundle {})
  val dut = LazyModule(new ysyxSoCFull)
  val mdut = Module(dut.module)
  mdut.dontTouchPorts() // mark all ports as don't touch
  mdut.externalPins := DontCare
}
