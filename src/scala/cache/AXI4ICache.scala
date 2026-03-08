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

class axi4_icache(params: AXI4BundleParameters) extends Module {
  val io = IO(new AXI4ICacheIO(params))
  val fence_i = IO(Input(Bool()))

  // write related signals (in)
  io.in.aw.ready := false.B
  io.in.w.ready := false.B
  io.in.b.valid := false.B; io.in.b.bits := DontCare
  // write related signals (out)
  io.out.aw.valid := false.B; io.out.aw.bits := DontCare
  io.out.w.valid := false.B; io.out.w.bits := DontCare
  io.out.b.ready := false.B

  // --- state ---
  object State extends ChiselEnum {
    val idle, lookup, refill, r_wait, ar_wait = Value
  }
  private val stateQ = RegInit(State.idle)

  // --- registers ---
  // latch from cpu, no burst
  private val rAddrQ = Reg(UInt(params.addrBits.W))
  private val rIdQ = Reg(UInt(params.idBits.W))

  // latch from cache or bus
  private val rDataQ = Reg(UInt(params.dataBits.W))

  // read related default signals (in)
  io.in.ar.ready := (stateQ === State.idle)
  io.in.r.valid := (stateQ === State.r_wait) // valid: lookup -> idle || refill -> idle
  io.in.r.bits.data := rDataQ
  io.in.r.bits.id := rIdQ
  io.in.r.bits.resp := 0.U
  io.in.r.bits.last := true.B
  assert( io.in.ar.bits.addr(1, 0) === "b00".U, "ICache: address must be aligned to 4 bytes")

  // read related default signals (out)
  io.out.ar.valid := (stateQ === State.ar_wait) // valid: lookup -> refill
  io.out.ar.bits.id := rIdQ
  io.out.ar.bits.addr := rAddrQ
  io.out.ar.bits.len := "b1111".U // 16 beats
  io.out.ar.bits.size := "b010".U // 2^2 = 4 bytes per beat
  io.out.ar.bits.burst := "b10".U // WRAP burst
  io.out.ar.bits.lock := 0.U
  io.out.ar.bits.cache := 0.U
  io.out.ar.bits.prot := 0.U
  io.out.ar.bits.qos := 0.U
  io.out.r.ready := (stateQ === State.refill)

  // counter
  private val addrMidQ = RegInit(0.U(4.W)) // addr(5, 2)
  private val addrHighQ = RegInit(0.U((params.addrBits - 6).W))

  // --- modules ---
  val core = Module(new ICacheCore(params))
  core.io.lookup.req_w := io.in.ar.fire
  core.io.lookup.addr_w := io.in.ar.bits.addr
  core.io.refill.valid_w := io.out.r.fire
  core.io.refill.addr_w := Cat( addrHighQ, addrMidQ, "b00".U(2.W) )
  core.io.refill.data_w := io.out.r.bits.data

  private val hitW = core.io.lookup.hit_q
  private val dataW = core.io.lookup.data_q

  // --- state machine ---
  switch(stateQ) {

    is(State.idle) {
      when(io.in.ar.fire) {
        stateQ := State.lookup
        rAddrQ := io.in.ar.bits.addr // latch
        rIdQ := io.in.ar.bits.id
      }
    }

    is(State.lookup) {
      when(hitW) {
        io.in.r.valid := true.B
        when(io.in.r.fire) {
          stateQ := State.idle
          io.in.r.bits.data := dataW
        } .otherwise {
          stateQ := State.r_wait
          rDataQ := dataW
        }
      } .otherwise {
        io.out.ar.valid := true.B
        addrMidQ := rAddrQ(5, 2)
        addrHighQ := rAddrQ((params.addrBits - 1), 6)
        when(io.out.ar.fire) {
          stateQ := State.refill
        } .otherwise {
          stateQ := State.ar_wait
        }
      }
    }

    is(State.ar_wait) {
      when(io.out.ar.fire) {
        stateQ := State.refill
      }
    }

    is(State.r_wait) {
      when(io.in.r.fire) {
        stateQ := State.idle
      }
    }

    is(State.refill) {
      when( io.out.r.fire ) {
        when( rAddrQ(5, 2) === addrMidQ ) { // first beat
          rDataQ := io.out.r.bits.data
        }

        addrMidQ := addrMidQ + 1.U(4.W)
        when( io.out.r.bits.last ) {
          io.in.r.valid := true.B
          when( io.in.r.fire ) {
            stateQ := State.idle
          } .otherwise {
            stateQ := State.r_wait
          }
        }
      }
    }

  }

}

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

class AXI4ICache(implicit p: Parameters) extends LazyModule {

  val node = AXI4IdentityNode()

  lazy val module = new Impl
  class Impl extends LazyModuleImp(this) {
    val fence_i = IO(Input(Bool()))
    (node.in zip node.out) foreach { case ((in, edgeIn), (out, edgeOut)) =>
      val params = edgeIn.bundle
      val cache = Module(new axi4_icache(params))
      cache.io.in <> in
      out <> cache.io.out

      // fence_i
      cache.fence_i := fence_i
    }
  }

}
