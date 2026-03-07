package ysyx

import chisel3._
import chisel3.util._
import chisel3.util.circt.dpi._

import org.chipsalliance.cde.config.Parameters
import freechips.rocketchip.amba._
import freechips.rocketchip.amba.axi4._
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.util._

class AXI4ICacheIO(params: AXI4BundleParameters) extends Bundle {
  val in = Flipped(new AXI4Bundle(params))
  val out = new AXI4Bundle(params)
}

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

class axi4_icache(params: AXI4BundleParameters) extends Module {
  val io = IO(new AXI4ICacheIO(params))
  io.in <> io.out

  // --- state ---
  object State extends ChiselEnum {
    val idle, lookup, refill = Value
  }
  object RefillState extends ChiselEnum {
    val idle, burst = Value
  }
  private val stateQ = RegInit(State.idle)
  private val refillStateQ = RegInit(RefillState.idle)

  // --- registers ---
  // from cpu, no burst
  private val rAddrQ = Reg(UInt(params.addrBits.W))
  private val rIdQ = Reg(UInt(params.idBits.W))

  // --- outputs ---
  io.in.ar.ready := (stateQ === State.idle)
  io.in.r.valid := false.B // default

  // --- modules ---
  val icache_lookup = Module(new icache_lookup(params))
  icache_lookup.io.clock := clock
  icache_lookup.io.req_w := io.in.ar.fire
  icache_lookup.io.addr_w := io.in.ar.bits.addr

  private val hitW = icache_lookup.io.hit_q
  private val dataW = icache_lookup.io.data_q

  // --- state machine ---
  switch(stateQ) {

    is(State.idle) {
      when(io.in.ar.fire) {
        stateQ := State.lookup
      }
    }

    is(State.lookup) {
      when(hitW) {
        stateQ := State.idle
      } .otherwise {
        stateQ := State.refill
      }
    }

    is(State.refill) {}

  }

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
