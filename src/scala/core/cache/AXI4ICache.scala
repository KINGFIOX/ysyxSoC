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

class Set extends Bundle {
  val tags = Vec(4, UInt(20.W))
  val valids = Vec(4, Bool())
  val plru = Vec(3, Bool())
}

// cacheline: 64B
// 4-way set associative
// tree-PLRU replacement policy
// 16KB/32KB/64KB cache size -> 256 cachelines -> 64 sets
class ICacheImpl(
    sramParams: SRAMBundleParameters,
    axiParams: AXI4BundleParameters
) extends Module {
  val io = IO(new Bundle {
    val in = Flipped(new SRAMBundle(sramParams))
    val out = new AXI4Bundle(axiParams)
  })
  val fence_i = IO(Input(Bool()))

  val in = io.in
  val out = io.out

  val offset = in.addr(5, 0)
  val index = in.addr(11, 6)
  val tag = in.addr(31, 12)

  val tag_ram = Reg(Vec(64, new Set))
  val data_bank_sram = Reg(Vec(64, Vec(4, Vec(16, UInt(32.W)))))

  val offset_reg = Reg(UInt(6.W))
  val index_reg = Reg(UInt(6.W))
  val tag_reg = Reg(UInt(20.W))
  val replace_way_reg = Reg(UInt(2.W))
  val beat_counter = RegInit(0.U(4.W))
  val refill_data = Reg(UInt(32.W))

  // hit detection
  val set = tag_ram(index_reg)
  val hit_vec = VecInit((0 until 4).map(i => set.valids(i) && (set.tags(i) === tag_reg)))
  val hit = hit_vec.asUInt.orR
  val hit_way = OHToUInt(hit_vec)

  // tree-PLRU: select replacement way by following tree pointers
  val plru = set.plru
  val replace_way = Cat(!plru(0), Mux(!plru(0), !plru(2), !plru(1)))

  // SRAM interface defaults
  in.ack := false.B
  in.done := false.B
  in.rdata := 0.U

  // AXI4 AR channel defaults
  out.ar.valid := false.B
  out.ar.bits := DontCare
  out.ar.bits.id := 0.U
  out.ar.bits.addr := Cat(tag_reg, index_reg, offset_reg(5, 2), 0.U(2.W))
  out.ar.bits.len := 15.U
  out.ar.bits.size := 2.U
  out.ar.bits.burst := AXI4Parameters.BURST_WRAP
  out.ar.bits.lock := 0.U
  out.ar.bits.cache := 0.U
  out.ar.bits.prot := 0.U
  out.ar.bits.qos := 0.U

  // AXI4 R channel default
  out.r.ready := false.B

  // AXI4 write channels tie off
  out.aw.valid := false.B
  out.aw.bits := DontCare
  out.w.valid := false.B
  out.w.bits := DontCare
  out.b.ready := false.B

  object State extends ChiselEnum {
    val idle, lookup, replace, refill = Value
  }

  val state_q = RegInit(State.idle)

  switch(state_q) {

    is(State.idle) {
      when(fence_i) {
        for (i <- 0 until 64; j <- 0 until 4) {
          tag_ram(i).valids(j) := false.B
        }
      }.elsewhen(in.req) {
        state_q := State.lookup
        in.ack := true.B
        offset_reg := offset
        index_reg := index
        tag_reg := tag
      }
    }

    is(State.lookup) {
      when(hit) {
        state_q := State.idle
        in.done := true.B
        in.rdata := data_bank_sram(index_reg)(hit_way)(offset_reg(5, 2))
        tag_ram(index_reg).plru(0) := hit_way(1)
        when(!hit_way(1)) {
          tag_ram(index_reg).plru(1) := hit_way(0)
        }.otherwise {
          tag_ram(index_reg).plru(2) := hit_way(0)
        }
      }.otherwise {
        state_q := State.replace
        replace_way_reg := replace_way
      }
    }

    is(State.replace) {
      out.ar.valid := true.B
      when(out.ar.fire) {
        state_q := State.refill
        beat_counter := 0.U
      }
    }

    is(State.refill) {
      val word_idx = (offset_reg(5, 2) + beat_counter)(3, 0)
      out.r.ready := true.B
      when(out.r.fire) {
        data_bank_sram(index_reg)(replace_way_reg)(word_idx) := out.r.bits.data
        when(beat_counter === 0.U) {
          refill_data := out.r.bits.data
        }
        beat_counter := beat_counter + 1.U
        when(out.r.bits.last) {
          tag_ram(index_reg).tags(replace_way_reg) := tag_reg
          tag_ram(index_reg).valids(replace_way_reg) := true.B
          tag_ram(index_reg).plru(0) := replace_way_reg(1)
          when(!replace_way_reg(1)) {
            tag_ram(index_reg).plru(1) := replace_way_reg(0)
          }.otherwise {
            tag_ram(index_reg).plru(2) := replace_way_reg(0)
          }
          state_q := State.idle
          in.done := true.B
          in.rdata := refill_data
        }
      }
    }

  }

}

class AXI4ICache(implicit p: Parameters) extends LazyModule {

  val node = SRAMToAXI4Node()

  lazy val module = new Impl
  class Impl extends LazyModuleImp(this) {
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
