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

// PIPT DCache: 64B cacheline, 4-way set associative, FIFO replacement, write-back
// 64 sets, offset 6 bits, index 6 bits, tag = addrBits - 12
// 8 beats x 8B = 64B per cacheline (64-bit data bus)
// Main FSM: IDLE -> LOOKUP -> MISS -> REPLACE -> REFILL
// Write Buffer FSM: WB_IDLE -> WB_WRITE
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

  // ===== Storage =====
  class CacheSet extends Bundle {
    val tags = Vec(nWays, UInt(tagBits.W))
    val valids = Vec(nWays, Bool())
    val dirtys = Vec(nWays, Bool())
  }

  val tag_ram = Reg(Vec(nSets, new CacheSet))
  val data_bank = Reg(Vec(nSets, Vec(nWays, Vec(nBeats, UInt(dataBits.W)))))
  val replace_ptrs = RegInit(VecInit(Seq.fill(nSets)(0.U(2.W))))

  // ===== Registered request (latched in IDLE) =====
  val index_reg = Reg(UInt(indexBits.W))
  val tag_reg = Reg(UInt(tagBits.W))
  val target_beat_reg = Reg(UInt(beatIdxBits.W))
  val replace_way_reg = Reg(UInt(2.W))
  val old_tag_reg = Reg(UInt(tagBits.W))
  val beat_counter = RegInit(0.U(beatIdxBits.W))
  val refill_data = Reg(UInt(dataBits.W))
  val victim_dirty_reg = Reg(Bool())

  val wen_reg = Reg(Bool())
  val wdata_reg = Reg(UInt(dataBits.W))
  val wstrb_reg = Reg(UInt((dataBits / 8).W))

  // ===== Hit detection (on registered address, used in LOOKUP) =====
  val set_reg = tag_ram(index_reg)
  val hit_vec = VecInit((0 until nWays).map(i => set_reg.valids(i) && (set_reg.tags(i) === tag_reg)))
  val hit = hit_vec.asUInt.orR
  val hit_way = OHToUInt(hit_vec)

  // ===== Write Buffer =====
  object WBState extends ChiselEnum {
    val wb_idle, wb_write = Value
  }
  val wb_state = RegInit(WBState.wb_idle)
  val wb_index = Reg(UInt(indexBits.W))
  val wb_way = Reg(UInt(2.W))
  val wb_beat = Reg(UInt(beatIdxBits.W))
  val wb_wstrb = Reg(UInt((dataBits / 8).W))
  val wb_wdata = Reg(UInt(dataBits.W))

  val hit_write_trigger = WireDefault(false.B)

  switch(wb_state) {
    is(WBState.wb_idle) {
      when(hit_write_trigger) {
        wb_state := WBState.wb_write
      }
    }
    is(WBState.wb_write) {
      val mask = FillInterleaved(8, wb_wstrb)
      val old_data = data_bank(wb_index)(wb_way)(wb_beat)
      data_bank(wb_index)(wb_way)(wb_beat) := (old_data & ~mask) | (wb_wdata & mask)
      tag_ram(wb_index).dirtys(wb_way) := true.B
      when(hit_write_trigger) {
        wb_state := WBState.wb_write
      }.otherwise {
        wb_state := WBState.wb_idle
      }
    }
  }

  // Hit-Write conflict: Write Buffer is writing and new load overlaps
  val wb_conflict = (wb_state === WBState.wb_write) && !in.wen &&
    (in.addr(offsetBits - 1, dataBytesBits) === wb_beat) &&
    (in.addr(offsetBits + indexBits - 1, offsetBits) === wb_index)

  // ===== Flush state =====
  val flushing = RegInit(false.B)
  val flush_set = RegInit(0.U(indexBits.W))
  val flush_way = RegInit(0.U(2.W))

  // ===== SRAM interface defaults =====
  in.ack := false.B
  in.done := false.B
  in.rdata := 0.U

  // ===== AXI4 AR defaults (refill read) =====
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

  // ===== AXI4 AW defaults (dirty writeback) =====
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

  // ===== AXI4 W defaults =====
  out.w.valid := false.B
  out.w.bits := DontCare
  out.w.bits.data := data_bank(index_reg)(replace_way_reg)(beat_counter)
  out.w.bits.strb := ((1 << (dataBits / 8)) - 1).U
  out.w.bits.last := beat_counter === (nBeats - 1).U

  // ===== AXI4 R/B defaults =====
  out.r.ready := false.B
  out.b.ready := false.B

  // ===== Main FSM =====
  object State extends ChiselEnum {
    val idle, lookup, miss, replace, refill, flush_scan, flush_wb = Value
  }
  val state_q = RegInit(State.idle)

  // REPLACE sub-phase tracking
  val wb_burst_done = RegInit(false.B)
  val b_received = RegInit(false.B)
  val ar_sent = RegInit(false.B)

  switch(state_q) {

    // ===================== IDLE =====================
    is(State.idle) {
      when(fence_i || sfence_vma) {
        flushing := true.B
        flush_set := 0.U
        flush_way := 0.U
        state_q := State.flush_scan
      }.elsewhen(in.req && !wb_conflict) {
        in.ack := true.B
        index_reg := in.addr(offsetBits + indexBits - 1, offsetBits)
        tag_reg := in.addr(sramAddrBits - 1, offsetBits + indexBits)
        target_beat_reg := in.addr(offsetBits - 1, dataBytesBits)
        wen_reg := in.wen
        wdata_reg := in.wdata
        wstrb_reg := in.wstrb
        state_q := State.lookup
      }
    }

    // ===================== LOOKUP =====================
    is(State.lookup) {
      when(hit) {
        in.done := true.B
        when(wen_reg) {
          // Hit write: trigger Write Buffer
          hit_write_trigger := true.B
          wb_index := index_reg
          wb_way := hit_way
          wb_beat := target_beat_reg
          wb_wstrb := wstrb_reg
          wb_wdata := wdata_reg
        }.otherwise {
          in.rdata := data_bank(index_reg)(hit_way)(target_beat_reg)
        }
        // LOOKUP -> LOOKUP or LOOKUP -> IDLE
        when(in.req && !wb_conflict) {
          in.ack := true.B
          index_reg := in.addr(offsetBits + indexBits - 1, offsetBits)
          tag_reg := in.addr(sramAddrBits - 1, offsetBits + indexBits)
          target_beat_reg := in.addr(offsetBits - 1, dataBytesBits)
          wen_reg := in.wen
          wdata_reg := in.wdata
          wstrb_reg := in.wstrb
          state_q := State.lookup
        }.otherwise {
          state_q := State.idle
        }
      }.otherwise {
        // Miss: record victim
        val victim = replace_ptrs(index_reg)
        replace_way_reg := victim
        old_tag_reg := set_reg.tags(victim)
        victim_dirty_reg := set_reg.valids(victim) && set_reg.dirtys(victim)
        state_q := State.miss
      }
    }

    // ===================== MISS =====================
    is(State.miss) {
      when(victim_dirty_reg) {
        out.aw.valid := true.B
        when(out.aw.fire) {
          state_q := State.replace
          beat_counter := 0.U
          wb_burst_done := false.B
          b_received := false.B
          ar_sent := false.B
        }
      }.otherwise {
        state_q := State.replace
        beat_counter := 0.U
        wb_burst_done := true.B
        b_received := true.B
        ar_sent := false.B
      }
    }

    // ===================== REPLACE =====================
    // Phase 1: Send W burst (if dirty). Phase 2: Wait B (if dirty). Phase 3: Send AR.
    is(State.replace) {
      // W burst
      when(!wb_burst_done) {
        out.w.valid := true.B
        when(out.w.fire) {
          beat_counter := beat_counter + 1.U
          when(out.w.bits.last) {
            wb_burst_done := true.B
          }
        }
      }

      // B response (can overlap with AR)
      when(wb_burst_done && !b_received) {
        out.b.ready := true.B
        when(out.b.fire) {
          b_received := true.B
        }
      }

      // AR (only after W burst done; B can still be in flight but we've committed the write)
      when(wb_burst_done && !ar_sent) {
        out.ar.valid := true.B
        when(out.ar.fire) {
          ar_sent := true.B
        }
      }

      // Transition to REFILL when AR sent and B received
      when(ar_sent && b_received) {
        state_q := State.refill
        beat_counter := 0.U
      }
    }

    // ===================== REFILL =====================
    is(State.refill) {
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
          replace_ptrs(index_reg) := replace_ptrs(index_reg) + 1.U
          state_q := State.idle
          in.done := true.B
          when(!wen_reg) {
            in.rdata := Mux(beat_counter === target_beat_reg, out.r.bits.data, refill_data)
          }
        }
      }
    }

    // ===================== FLUSH: scan all sets/ways for dirty lines =====================
    is(State.flush_scan) {
      val fs = tag_ram(flush_set)
      val fw_dirty = fs.valids(flush_way) && fs.dirtys(flush_way)
      when(fw_dirty) {
        index_reg := flush_set
        replace_way_reg := flush_way
        old_tag_reg := fs.tags(flush_way)
        out.aw.valid := true.B
        out.aw.bits.addr := Cat(fs.tags(flush_way), flush_set, 0.U(offsetBits.W))
        when(out.aw.fire) {
          beat_counter := 0.U
          wb_burst_done := false.B
          b_received := false.B
          state_q := State.flush_wb
        }
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

    // ===================== FLUSH_WB: W burst + B for a dirty line during flush =====================
    is(State.flush_wb) {
      when(!wb_burst_done) {
        out.w.valid := true.B
        when(out.w.fire) {
          beat_counter := beat_counter + 1.U
          when(out.w.bits.last) {
            wb_burst_done := true.B
          }
        }
      }

      when(wb_burst_done && !b_received) {
        out.b.ready := true.B
        when(out.b.fire) {
          b_received := true.B
        }
      }

      when(wb_burst_done && b_received) {
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
      }
    }

  }
}

class AXI4DCache(id: Int)(implicit p: Parameters) extends LazyModule {

  val node = SRAMToAXI4Node(endId = id + 1)

  lazy val module = new Impl
  class Impl extends LazyModuleImp(this) {
    val fence_i = IO(Input(Bool()))
    val sfence_vma = IO(Input(Bool()))
    (node.in zip node.out) foreach { case ((in, edgeIn), (out, edgeOut)) =>
      val sramParams = edgeIn.bundle
      val axiParams = edgeOut.bundle
      val cache = Module(new DCacheImpl(id = id, sramParams, axiParams))
      cache.io.in <> in
      out <> cache.io.out
      cache.fence_i := fence_i
      cache.sfence_vma := sfence_vma
    }
  }

}
