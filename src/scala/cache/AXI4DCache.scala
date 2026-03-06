package ysyx

import chisel3._
import chisel3.util._

import org.chipsalliance.cde.config.Parameters
import freechips.rocketchip.amba._
import freechips.rocketchip.amba.axi4._
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.util._

class AXI4DCacheIO(params: AXI4BundleParameters) extends Bundle {
  val in = Flipped(new AXI4Bundle(params))
  val out = new AXI4Bundle(params)
}

class axi4_dcache(params: AXI4BundleParameters) extends Module {
  val io = IO(new AXI4DCacheIO(params))
  io.in <> io.out
}

class AXI4DCache(implicit p: Parameters) extends LazyModule {

  val node = AXI4IdentityNode()

  lazy val module = new Impl
  class Impl extends LazyModuleImp(this) {
    (node.in zip node.out) foreach { case ((in, edgeIn), (out, edgeOut)) =>
      val params = edgeIn.bundle
      val cache = Module(new axi4_dcache(params))
      cache.io.in <> in
      out <> cache.io.out
    }
  }

}

object AXI4DCache {
  def apply()(implicit p: Parameters): AXI4Node = {
    val axi4cache = LazyModule(new AXI4DCache)
    axi4cache.node
  }
}
