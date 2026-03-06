package ysyx

import chisel3._
import chisel3.util._

import org.chipsalliance.cde.config.Parameters
import freechips.rocketchip.amba._
import freechips.rocketchip.amba.axi4._
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.util._

class AXI4ICacheIO(params: AXI4BundleParameters) extends Bundle {
  val in = Flipped(new AXI4Bundle(params))
  val out = new AXI4Bundle(params)
}

class axi4_icache(params: AXI4BundleParameters) extends Module {
  val io = IO(new AXI4ICacheIO(params))
  io.in <> io.out
}

class AXI4ICache(implicit p: Parameters) extends LazyModule {

  val node = AXI4IdentityNode()

  lazy val module = new Impl
  class Impl extends LazyModuleImp(this) {
    (node.in zip node.out) foreach { case ((in, edgeIn), (out, edgeOut)) =>
      val params = edgeIn.bundle
      val cache = Module(new axi4_icache(params))
      cache.io.in <> in
      out <> cache.io.out
    }
  }

}

object AXI4ICache {
  def apply()(implicit p: Parameters): AXI4Node = {
    val axi4cache = LazyModule(new AXI4ICache)
    axi4cache.node
  }
}
