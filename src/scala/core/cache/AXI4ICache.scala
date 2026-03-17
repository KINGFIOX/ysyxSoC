package ysyx.core.cache

import chisel3._
import chisel3.util._
import chisel3.util.circt.dpi._

import org.chipsalliance.cde.config.Parameters
import freechips.rocketchip.amba._
import freechips.rocketchip.amba.axi4._
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.util._

import ysyx.core.sram._

class icache_lookup(params: AXI4BundleParameters)
    extends FixedIOExtModule(
      new Bundle {
        val clock = Input(Clock())
        val req_w = Input(Bool())
        val addr_w = Input(UInt(params.addrBits.W))
        val hit_q = Output(Bool())
        val data_q = Output(UInt(params.dataBits.W))
      },
      Map("ADDR_BITS" -> params.addrBits, "DATA_BITS" -> params.dataBits)
    )

class icache_refill(params: AXI4BundleParameters)
    extends FixedIOExtModule(
      new Bundle {
        val clock = Input(Clock())
        val valid_w = Input(Bool())
        val addr_w = Input(UInt(params.addrBits.W))
        val data_w = Input(UInt(params.dataBits.W))
      },
      Map("ADDR_BITS" -> params.addrBits, "DATA_BITS" -> params.dataBits)
    )

// cacheline: 64B
// 4-way set associative
// tree-PLRU replacement policy
// 16KB/32KB/64KB cache size
class ICacheCore(params: AXI4BundleParameters) extends Module {
  val io = IO(new Bundle {
    val lookup = new Bundle {
      val req_w = Input(Bool())
      val addr_w = Input(UInt(params.addrBits.W))
      val hit_q = Output(Bool())
      val data_q = Output(UInt(params.dataBits.W))
    }
    val refill = new Bundle {
      val valid_w = Input(Bool())
      val addr_w = Input(UInt(params.addrBits.W))
      val data_w = Input(UInt(params.dataBits.W))
    }
  })
  private val icache_lookup = Module(new icache_lookup(params))
  private val icache_refill = Module(new icache_refill(params))
  icache_lookup.io.clock := clock
  icache_lookup.io.req_w := io.lookup.req_w
  icache_lookup.io.addr_w := io.lookup.addr_w
  io.lookup.hit_q := icache_lookup.io.hit_q
  io.lookup.data_q := icache_lookup.io.data_q
  icache_refill.io.clock := clock
  icache_refill.io.valid_w := io.refill.valid_w
  icache_refill.io.addr_w := io.refill.addr_w
  icache_refill.io.data_w := io.refill.data_w
}

class ICacheImpl(
    sramParams: SRAMBundleParameters,
    axiParams: AXI4BundleParameters
) extends Module {
  val io = IO(new Bundle {
    val in = Flipped(new SRAMBundle(sramParams))
    val out = new AXI4Bundle(axiParams)
  })
  val fence_i = IO(Input(Bool()))
}

class AXI4ICache(implicit p: Parameters) extends LazyModule {

  val node = SRAMToAXI4Node()

  lazy val module = new LazyModuleImp(this) {
    val fence_i = IO(Input(Bool()))
    (node.in zip node.out) foreach { case ((in, edgeIn), (out, edgeOut)) =>
      val sramParams = edgeIn.bundle
      val axiParams = edgeOut.bundle
      val cache = Module(new ICacheImpl(sramParams, axiParams))
      cache.io.in <> in
      out <> cache.io.out

      // fence_i
      cache.fence_i := fence_i
    }
  }

}
