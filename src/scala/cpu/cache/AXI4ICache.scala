package ysyx.cpu.cache

import chisel3._
import chisel3.util._

import org.chipsalliance.cde.config.Parameters
import freechips.rocketchip.amba._
import freechips.rocketchip.amba.axi4._
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.util._

import ysyx.core.sram._
import ysyx.core.common.{HasCoreParameter, PerfEvent}

// PIPT ICache: 64B cacheline, 4-way set associative, FIFO replacement
// 64 sets, offset 6 bits, index 6 bits, tag = addrBits - 12
// 8 beats x 8B = 64B per cacheline (64-bit data bus)
// Main FSM: IDLE -> LOOKUP -> MISS -> REPLACE -> REFILL
class ICacheImpl(
    id: Int,
    sramParams: SRAMBundleParameters,
    axiParams: AXI4BundleParameters
) extends Module with HasCoreParameter {
  val io = IO(new Bundle {
    val in = Flipped(new SRAMBundle(sramParams))
    val out = new AXI4Bundle(axiParams)
  })
  val fence_i = IO(Input(Bool()))

  private val offsetBits = 6
  private val indexBits = 6
  private val nSets = 64
  private val nWays = 4
  private val sramAddrBits = sramParams.addrBits
  private val tagBits = sramAddrBits - offsetBits - indexBits
  private val nBeats = 8
  private val beatIdxBits = log2Ceil(nBeats)

  val in = io.in
  val out = io.out

  // ----- Storage -----
  class CacheSet extends Bundle {
    val tags = Vec(nWays, UInt(tagBits.W))
    val valids = Vec(nWays, Bool())
  }

  val tag_ram = Reg(Vec(nSets, new CacheSet))
  val data_bank = Reg(Vec(nSets, Vec(nWays, Vec(nBeats, UInt(dataBits.W)))))
  val replace_ptrs = RegInit(VecInit(Seq.fill(nSets)(0.U(2.W))))

  // ----- Registered request (latched in IDLE) -----
  val index_reg = Reg(UInt(indexBits.W))
  val tag_reg = Reg(UInt(tagBits.W))
  val target_beat_reg = Reg(UInt(beatIdxBits.W))
  val replace_way_reg = Reg(UInt(2.W))
  val beat_counter = RegInit(0.U(beatIdxBits.W))
  val refill_data = Reg(UInt(dataBits.W))

  // ----- Hit detection (on registered address, used in LOOKUP) -----
  val set_reg = tag_ram(index_reg)
  val hit_vec = VecInit((0 until nWays).map(i => set_reg.valids(i) && (set_reg.tags(i) === tag_reg)))
  val hit = hit_vec.asUInt.orR
  val hit_way = OHToUInt(hit_vec)

  // ----- SRAM interface defaults -----
  in.ack := false.B
  in.done := false.B
  in.rdata := 0.U

  // ----- AXI4 AR defaults -----
  out.ar.valid := false.B
  out.ar.bits := DontCare
  out.ar.bits.id := id.U
  out.ar.bits.addr := Cat(tag_reg, index_reg, 0.U(offsetBits.W))
  out.ar.bits.len := (nBeats - 1).U
  out.ar.bits.size := dataBytesBits.U
  out.ar.bits.burst := AXI4Parameters.BURST_INCR
  out.ar.bits.lock := 0.U
  out.ar.bits.cache := 0.U
  out.ar.bits.prot := 0.U
  out.ar.bits.qos := 0.U

  // AXI4 R default
  out.r.ready := false.B

  // AXI4 write channels tie off (ICache is read-only)
  out.aw.valid := false.B
  out.aw.bits := DontCare
  out.w.valid := false.B
  out.w.bits := DontCare
  out.b.ready := false.B

  // ----- Main FSM -----
  object State extends ChiselEnum {
    val idle, lookup, miss, replace, refill = Value
  }
  val state_q = RegInit(State.idle)

  // ----- Perf events (DPI-C, sim only) -----
  val is_lookup = state_q === State.lookup
  val is_miss_cycle = state_q === State.miss || state_q === State.replace || state_q === State.refill
  PerfEvent(PerfEvent.ICACHE_ACCESS, is_lookup)
  PerfEvent(PerfEvent.ICACHE_HIT,    is_lookup &&  hit)
  PerfEvent(PerfEvent.ICACHE_MISS,   is_lookup && !hit)
  PerfEvent(PerfEvent.ICACHE_MISS_CYCLES, is_miss_cycle)

  switch(state_q) {

    is(State.idle) {
      when(fence_i) {
        for (i <- 0 until nSets; j <- 0 until nWays) {
          tag_ram(i).valids(j) := false.B
        }
      }.elsewhen(in.req) {
        in.ack := true.B
        index_reg := in.addr(offsetBits + indexBits - 1, offsetBits)
        tag_reg := in.addr(sramAddrBits - 1, offsetBits + indexBits)
        target_beat_reg := in.addr(offsetBits - 1, dataBytesBits)
        state_q := State.lookup
      }
    }

    is(State.lookup) {
      when(hit) {
        in.done := true.B
        in.rdata := data_bank(index_reg)(hit_way)(target_beat_reg)
        when(in.req) {
          in.ack := true.B
          index_reg := in.addr(offsetBits + indexBits - 1, offsetBits)
          tag_reg := in.addr(sramAddrBits - 1, offsetBits + indexBits)
          target_beat_reg := in.addr(offsetBits - 1, dataBytesBits)
          state_q := State.lookup
        }.otherwise {
          state_q := State.idle
        }
      }.otherwise {
        replace_way_reg := replace_ptrs(index_reg)
        state_q := State.miss
      }
    }

    is(State.miss) {
      // ICache: wr_rdy always 1, single-cycle pass-through
      state_q := State.replace
    }

    is(State.replace) {
      out.ar.valid := true.B
      when(out.ar.fire) {
        state_q := State.refill
        beat_counter := 0.U
      }
    }

    is(State.refill) {
      out.r.ready := true.B
      when(out.r.fire) {
        data_bank(index_reg)(replace_way_reg)(beat_counter) := out.r.bits.data
        when(beat_counter === target_beat_reg) {
          refill_data := out.r.bits.data
        }
        beat_counter := beat_counter + 1.U
        when(out.r.bits.last) {
          tag_ram(index_reg).tags(replace_way_reg) := tag_reg
          tag_ram(index_reg).valids(replace_way_reg) := true.B
          replace_ptrs(index_reg) := replace_ptrs(index_reg) + 1.U
          state_q := State.idle
          in.done := true.B
          in.rdata := Mux(beat_counter === target_beat_reg, out.r.bits.data, refill_data)
        }
      }
    }

  }

}

class AXI4ICache(id: Int)(implicit p: Parameters) extends LazyModule {

  val node = SRAMToAXI4Node(endId = id + 1)

  lazy val module = new Impl
  class Impl extends LazyModuleImp(this) {
    val fence_i = IO(Input(Bool()))
    (node.in zip node.out) foreach { case ((in, edgeIn), (out, edgeOut)) =>
      val sramParams = edgeIn.bundle
      val axiParams = edgeOut.bundle
      val cache = Module(new ICacheImpl(id = id, sramParams, axiParams))
      cache.io.in <> in
      out <> cache.io.out
      cache.fence_i := fence_i
    }
  }

}
