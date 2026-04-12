package ysyx.cpu.cache

import chisel3._
import chisel3.util._

import org.chipsalliance.cde.config.Parameters
import freechips.rocketchip.amba._
import freechips.rocketchip.amba.axi4._
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.util._

import ysyx.core.sram._
import ysyx.core.common.HasCoreParameter

// PIPT DCache: 64B cacheline, 4-way set associative, tree-PLRU, write-back
// 64 sets, offset 6 bits, index 6 bits, tag = addrBits - 12
// 8 beats x 8B = 64B per cacheline (64-bit data bus)
// Single-cycle hit for both read and write; write-allocate on miss
class DCacheImpl(
    id: Int,
    sramParams: SRAMBundleParameters,
    axiParams: AXI4BundleParameters
) extends Module with HasCoreParameter {
  val io = IO(new Bundle {
    val in = Flipped(new SRAMBundle(sramParams))
    val out = new AXI4Bundle(axiParams)
  })
  val fence_i = IO(Input(Bool()))
  val sfence_vma = IO(Input(Bool()))

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

  // Combinational address decode (VIPT: index within page offset)
  val offset = in.addr(offsetBits - 1, 0)
  val index = in.addr(offsetBits + indexBits - 1, offsetBits)
  val tag = in.addr(sramAddrBits - 1, offsetBits + indexBits)
  val target_beat = offset(offsetBits - 1, dataBytesBits)

  class CacheSet extends Bundle {
    val tags = Vec(nWays, UInt(tagBits.W))
    val valids = Vec(nWays, Bool())
    val dirtys = Vec(nWays, Bool())
    val plru = Vec(3, Bool())
  }

  val tag_ram = Reg(Vec(nSets, new CacheSet))
  val data_bank = Reg(Vec(nSets, Vec(nWays, Vec(nBeats, UInt(dataBits.W)))))

  // Registered state for miss / writeback / refill
  val index_reg = Reg(UInt(indexBits.W))
  val tag_reg = Reg(UInt(tagBits.W))
  val target_beat_reg = Reg(UInt(beatIdxBits.W))
  val replace_way_reg = Reg(UInt(2.W))
  val old_tag_reg = Reg(UInt(tagBits.W))
  val beat_counter = RegInit(0.U(beatIdxBits.W))
  val refill_data = Reg(UInt(dataBits.W))

  // Registered write request (for write-allocate on miss)
  val wen_reg = Reg(Bool())
  val wdata_reg = Reg(UInt(dataBits.W))
  val wstrb_reg = Reg(UInt((dataBits / 8).W))

  // Flush state
  val flushing = RegInit(false.B)
  val flush_set = RegInit(0.U(indexBits.W))
  val flush_way = RegInit(0.U(2.W))

  // Combinational hit detection
  val set = tag_ram(index)
  val hit_vec = VecInit((0 until nWays).map(i => set.valids(i) && (set.tags(i) === tag)))
  val hit = hit_vec.asUInt.orR
  val hit_way = OHToUInt(hit_vec)

  // tree-PLRU replacement selection
  val plru = set.plru
  val replace_way = Cat(!plru(0), Mux(!plru(0), !plru(2), !plru(1)))

  // Write merge mask (byte-level)
  val wstrb_mask = FillInterleaved(8, in.wstrb)

  // SRAM interface defaults
  in.ack := false.B
  in.done := false.B
  in.rdata := 0.U

  // AXI4 AR defaults (for refill read)
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

  // AXI4 AW defaults (for dirty writeback)
  out.aw.valid := false.B
  out.aw.bits := DontCare
  out.aw.bits.id := id.U
  out.aw.bits.addr := Cat(old_tag_reg, index_reg, 0.U(offsetBits.W))
  out.aw.bits.len := (nBeats - 1).U
  out.aw.bits.size := dataBytesBits.U
  out.aw.bits.burst := AXI4Parameters.BURST_INCR
  out.aw.bits.lock := 0.U
  out.aw.bits.cache := 0.U
  out.aw.bits.prot := 0.U
  out.aw.bits.qos := 0.U

  // AXI4 W defaults
  out.w.valid := false.B
  out.w.bits := DontCare
  out.w.bits.data := data_bank(index_reg)(replace_way_reg)(beat_counter)
  out.w.bits.strb := ((1 << (dataBits / 8)) - 1).U
  out.w.bits.last := beat_counter === (nBeats - 1).U

  // AXI4 R/B defaults
  out.r.ready := false.B
  out.b.ready := false.B

  object State extends ChiselEnum {
    val idle, wb_addr, wb_data, wb_resp, refill_addr, refill_data, flush_scan = Value
  }
  val state_q = RegInit(State.idle)

  def updatePLRU(setIdx: UInt, way: UInt): Unit = {
    tag_ram(setIdx).plru(0) := way(1)
    when(!way(1)) {
      tag_ram(setIdx).plru(1) := way(0)
    }.otherwise {
      tag_ram(setIdx).plru(2) := way(0)
    }
  }

  switch(state_q) {

    is(State.idle) {
      when(fence_i || sfence_vma) {
        flushing := true.B
        flush_set := 0.U
        flush_way := 0.U
        state_q := State.flush_scan
      }.elsewhen(in.req) {
        when(hit) {
          in.ack := true.B
          in.done := true.B
          when(in.wen) {
            val old_data = data_bank(index)(hit_way)(target_beat)
            data_bank(index)(hit_way)(target_beat) := (old_data & ~wstrb_mask) | (in.wdata & wstrb_mask)
            tag_ram(index).dirtys(hit_way) := true.B
          }.otherwise {
            in.rdata := data_bank(index)(hit_way)(target_beat)
          }
          updatePLRU(index, hit_way)
        }.otherwise {
          in.ack := true.B
          index_reg := index
          tag_reg := tag
          target_beat_reg := target_beat
          wen_reg := in.wen
          wdata_reg := in.wdata
          wstrb_reg := in.wstrb

          val victim = replace_way
          replace_way_reg := victim
          old_tag_reg := set.tags(victim)

          when(set.valids(victim) && set.dirtys(victim)) {
            state_q := State.wb_addr
          }.otherwise {
            state_q := State.refill_addr
          }
        }
      }
    }

    // ===================== Dirty writeback via AXI burst write =====================

    is(State.wb_addr) {
      out.aw.valid := true.B
      when(out.aw.fire) {
        state_q := State.wb_data
        beat_counter := 0.U
      }
    }

    is(State.wb_data) {
      out.w.valid := true.B
      when(out.w.fire) {
        beat_counter := beat_counter + 1.U
        when(out.w.bits.last) {
          state_q := State.wb_resp
        }
      }
    }

    is(State.wb_resp) {
      out.b.ready := true.B
      when(out.b.fire) {
        when(flushing) {
          tag_ram(index_reg).dirtys(replace_way_reg) := false.B
          val way_wrap = flush_way === (nWays - 1).U
          val set_wrap = flush_set === (nSets - 1).U
          when(way_wrap) {
            flush_way := 0.U
            when(set_wrap) {
              flushing := false.B
              state_q := State.idle
            }.otherwise {
              flush_set := flush_set + 1.U
              state_q := State.flush_scan
            }
          }.otherwise {
            flush_way := flush_way + 1.U
            state_q := State.flush_scan
          }
        }.otherwise {
          state_q := State.refill_addr
        }
      }
    }

    // ===================== Cache line refill via AXI burst read =====================

    is(State.refill_addr) {
      out.ar.valid := true.B
      when(out.ar.fire) {
        state_q := State.refill_data
        beat_counter := 0.U
      }
    }

    is(State.refill_data) {
      out.r.ready := true.B
      when(out.r.fire) {
        val beat_data = out.r.bits.data
        when(wen_reg && beat_counter === target_beat_reg) {
          val merged_mask = FillInterleaved(8, wstrb_reg)
          data_bank(index_reg)(replace_way_reg)(beat_counter) := (beat_data & ~merged_mask) | (wdata_reg & merged_mask)
          refill_data := (beat_data & ~merged_mask) | (wdata_reg & merged_mask)
        }.otherwise {
          data_bank(index_reg)(replace_way_reg)(beat_counter) := beat_data
          when(beat_counter === target_beat_reg) {
            refill_data := beat_data
          }
        }
        beat_counter := beat_counter + 1.U
        when(out.r.bits.last) {
          tag_ram(index_reg).tags(replace_way_reg) := tag_reg
          tag_ram(index_reg).valids(replace_way_reg) := true.B
          tag_ram(index_reg).dirtys(replace_way_reg) := wen_reg
          updatePLRU(index_reg, replace_way_reg)
          state_q := State.idle
          in.done := true.B
          when(!wen_reg) {
            in.rdata := Mux(beat_counter === target_beat_reg, out.r.bits.data, refill_data)
          }
        }
      }
    }

    // ===================== Flush: scan all sets/ways for dirty lines =====================

    is(State.flush_scan) {
      val fs = tag_ram(flush_set)
      val fw_dirty = fs.valids(flush_way) && fs.dirtys(flush_way)
      when(fw_dirty) {
        index_reg := flush_set
        replace_way_reg := flush_way
        old_tag_reg := fs.tags(flush_way)
        state_q := State.wb_addr
      }.otherwise {
        val way_wrap = flush_way === (nWays - 1).U
        val set_wrap = flush_set === (nSets - 1).U
        when(way_wrap) {
          flush_way := 0.U
          when(set_wrap) {
            flushing := false.B
            state_q := State.idle
          }.otherwise {
            flush_set := flush_set + 1.U
          }
        }.otherwise {
          flush_way := flush_way + 1.U
        }
      }
    }

  }
}

class AXI4DCache(implicit p: Parameters) extends LazyModule {

  val node = SRAMToAXI4Node()

  lazy val module = new Impl
  class Impl extends LazyModuleImp(this) {
    val fence_i = IO(Input(Bool()))
    val sfence_vma = IO(Input(Bool()))
    (node.in zip node.out) foreach { case ((in, edgeIn), (out, edgeOut)) =>
      val sramParams = edgeIn.bundle
      val axiParams = edgeOut.bundle
      val cache = Module(new DCacheImpl(id = 1, sramParams, axiParams))
      cache.io.in <> in
      out <> cache.io.out
      cache.fence_i := fence_i
      cache.sfence_vma := sfence_vma
    }
  }

}
