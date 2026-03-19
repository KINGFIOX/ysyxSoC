package ysyx.amba

import chisel3._
import chisel3.util._

import org.chipsalliance.cde.config.Parameters
import freechips.rocketchip.amba._
import freechips.rocketchip.amba.axi4._
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.util._

class AXI4DelayerIO(params: AXI4BundleParameters) extends Bundle {
  val in = Flipped(new AXI4Bundle(params))
  val out = new AXI4Bundle(params)
}

class axi4_delayer(params: AXI4BundleParameters) extends Module {
  val io = IO(new AXI4DelayerIO(params))
  io.in <> io.out
}

class AXI4Delayer(implicit p: Parameters) extends LazyModule {
  val node = AXI4IdentityNode()

  lazy val module = new LazyModuleImp(this) {
    (node.in zip node.out) foreach { case ((in, edgeIn), (out, edgeOut)) =>
      val params = edgeIn.bundle
      val delayer = Module(new axi4_delayer(params))
      delayer.io.in <> in
      out <> delayer.io.out
    }
  }
}

object AXI4Delayer {
  def apply()(implicit p: Parameters): AXI4Node = {
    val axi4delay = LazyModule(new AXI4Delayer)
    axi4delay.node
  }
}
