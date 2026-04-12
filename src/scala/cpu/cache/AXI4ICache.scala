package ysyx.cpu.cache

import chisel3._
import chisel3.util._
import chisel3.util.circt.dpi._

import org.chipsalliance.cde.config.Parameters
import freechips.rocketchip.amba._
import freechips.rocketchip.amba.axi4._
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.util._

import ysyx.core.sram._
import ysyx.core.common.HasCoreParameter

// PIPT ICache: 64B cacheline, 4-way set associative, tree-PLRU
// 64 sets, offset 6 bits, index 6 bits, tag = addrBits - 12
// 8 beats x 8B = 64B per cacheline (64-bit data bus)
// Single-cycle hit: combinational lookup from in.addr, ack+done in same cycle
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

  private val offsetBits = 6 // 64B cacheline
  private val indexBits = 6 // 64 sets
  private val sramAddrBits = sramParams.addrBits
  private val tagBits = sramAddrBits - offsetBits - indexBits
  private val nBeats = 8 // 8 beats x 8B = 64B
  private val beatIdxBits = log2Ceil(nBeats)

  val in = io.in
  val out = io.out

  // Combinational address decode from input (VIPT: index within page offset)
  val offset = in.addr(offsetBits - 1, 0)
  val index = in.addr(offsetBits + indexBits - 1, offsetBits)
  val tag = in.addr(sramAddrBits - 1, offsetBits + indexBits)
  val target_beat = offset(offsetBits - 1, dataBytesBits)

  class CacheSet extends Bundle {
    val tags = Vec(4, UInt(tagBits.W))
    val valids = Vec(4, Bool())
    val plru = Vec(3, Bool())
  }

  val tag_ram = Reg(Vec(64, new CacheSet))
  val data_bank_sram = Reg(Vec(64, Vec(4, Vec(nBeats, UInt(dataBits.W)))))

  // Registered address for refill path
  val index_reg = Reg(UInt(indexBits.W))
  val tag_reg = Reg(UInt(tagBits.W))
  val target_beat_reg = Reg(UInt(beatIdxBits.W))
  val replace_way_reg = Reg(UInt(2.W))
  val beat_counter = RegInit(0.U(beatIdxBits.W))
  val refill_data = Reg(UInt(dataBits.W))

  // Combinational hit detection (uses current in.addr, not registered)
  val set = tag_ram(index)
  val hit_vec = VecInit((0 until 4).map(i => set.valids(i) && (set.tags(i) === tag)))
  val hit = hit_vec.asUInt.orR
  val hit_way = OHToUInt(hit_vec)

  // tree-PLRU replacement selection
  val plru = set.plru
  val replace_way = Cat(!plru(0), Mux(!plru(0), !plru(2), !plru(1)))

  // SRAM interface defaults
  in.ack := false.B
  in.done := false.B
  in.rdata := 0.U

  // AXI4 AR channel defaults (uses registered values for refill)
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

  // AXI4 R channel default
  out.r.ready := false.B

  // AXI4 write channels tie off (ICache is read-only)
  out.aw.valid := false.B
  out.aw.bits := DontCare
  out.w.valid := false.B
  out.w.bits := DontCare
  out.b.ready := false.B

  object State extends ChiselEnum {
    val idle, replace, refill = Value
  }

  val state_q = RegInit(State.idle)

  switch(state_q) {

    is(State.idle) {
      when(fence_i) {
        for (i <- 0 until 64; j <- 0 until 4) {
          tag_ram(i).valids(j) := false.B
        }
      }.elsewhen(in.req) {
        when(hit) {
          in.ack := true.B
          in.done := true.B
          in.rdata := data_bank_sram(index)(hit_way)(target_beat)
          // Update tree-PLRU on hit
          tag_ram(index).plru(0) := hit_way(1)
          when(!hit_way(1)) {
            tag_ram(index).plru(1) := hit_way(0)
          }.otherwise {
            tag_ram(index).plru(2) := hit_way(0)
          }
        }.otherwise {
          in.ack := true.B
          index_reg := index
          tag_reg := tag
          target_beat_reg := target_beat
          replace_way_reg := replace_way
          state_q := State.replace
        }
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
      out.r.ready := true.B
      when(out.r.fire) {
        data_bank_sram(index_reg)(replace_way_reg)(beat_counter) := out.r.bits.data
        when(beat_counter === target_beat_reg) {
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
          in.rdata := Mux(beat_counter === target_beat_reg, out.r.bits.data, refill_data)
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
      val cache = Module(new ICacheImpl(id = 0, sramParams, axiParams))
      cache.io.in <> in
      out <> cache.io.out

      // fence_i
      cache.fence_i := fence_i
    }
  }

}
