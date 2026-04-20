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
//                                         \-- fence_i --> FENCE_DRAIN -> IDLE
//
// fence.i semantics (fence_i pulse asserted for 1 cycle from CommitStage):
//   * Any state: all `valids` are cleared the same cycle.  The front-end
//     therefore does NOT need a barrier; any in-flight fetch past fence.i
//     either finds its line already invalidated or is squashed by the
//     concurrent back-end `io.flush`.
//   * idle   : nothing else to do.
//   * lookup : no AR has been issued; drop straight back to idle.
//   * miss   : AR has NOT been issued yet (miss is a 1-cycle pass-through
//              into replace); drop straight back to idle.
//   * replace: AR is being offered this cycle (`out.ar.valid=1`).  If AR
//              also fires this cycle we MUST complete the AXI burst (no
//              way to cancel once `ar.fire`), so go to `fence_drain`;
//              otherwise AR is still just an offer and we can deassert
//              it next cycle by going back to idle.
//   * refill : AR has already fired and R beats are returning; we MUST
//              drain them.  Go to `fence_drain`.
//   * fence_drain: drain remaining R beats (`r.ready=1`) and do NOT
//                  write the cacheline back on `r.last`; return to idle.
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
    val idle, lookup, miss, replace, refill, fence_drain = Value
  }
  val state_q = RegInit(State.idle)

  // ----- fence_i globals -----
  // A one-cycle pulse clears valids in every state.  If an AXI burst is
  // in flight (or about to be kicked off this cycle), state machines
  // below also redirect into `fence_drain` so the burst can complete
  // without polluting the cache.
  val clear_all_valids = fence_i
  when(clear_all_valids) {
    for (i <- 0 until nSets; j <- 0 until nWays) {
      tag_ram(i).valids(j) := false.B
    }
  }

  // ----- Perf events (DPI-C, sim only) -----
  val is_lookup = state_q === State.lookup
  val is_miss_cycle = state_q === State.miss || state_q === State.replace || state_q === State.refill
  PerfEvent(PerfEvent.ICACHE_ACCESS, is_lookup)
  PerfEvent(PerfEvent.ICACHE_HIT,    is_lookup &&  hit)
  PerfEvent(PerfEvent.ICACHE_MISS,   is_lookup && !hit)
  PerfEvent(PerfEvent.ICACHE_MISS_CYCLES, is_miss_cycle)

  switch(state_q) {

    is(State.idle) {
      // valids already cleared above when fence_i pulses; nothing else
      // to do here since no AXI burst is in flight.
      when(in.req && !fence_i) {
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
      // fence_i: hit branch already cleared valids (so a concurrent
      // hit is fine — the read data was sampled from the old array).
      // Miss branch would have gone to miss, but now the old tag
      // inspection is moot: valids have been zeroed, so there was no
      // real hit anyway.  Simpler to just drop back to idle; the
      // upstream will be squashed by `io.flush` in the same cycle.
      when(fence_i) {
        state_q := State.idle
      }
    }

    is(State.miss) {
      // ICache: wr_rdy always 1, single-cycle pass-through
      state_q := State.replace
      // No AR has been issued yet; a concurrent fence_i can cancel
      // the pending refill outright.
      when(fence_i) {
        state_q := State.idle
      }
    }

    is(State.replace) {
      out.ar.valid := true.B
      when(out.ar.fire) {
        state_q := State.refill
        beat_counter := 0.U
      }
      // fence_i arrives while we are offering AR:
      //   * if ar.fire this cycle  -> burst has been launched, must drain.
      //   * if !ar.fire this cycle -> we can simply deassert AR next
      //                               cycle by returning to idle.
      // Note: `out.ar.valid` is held high this cycle regardless; that
      // is acceptable — no bus commitment until ar.fire.
      when(fence_i) {
        state_q := Mux(out.ar.fire, State.fence_drain, State.idle)
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
      // fence_i arriving mid-refill: we must keep draining R beats
      // (AXI does not allow cancelling a burst in flight) but MUST
      // NOT commit the line to the cache on r.last.  Transition to
      // fence_drain so the normal refill write-back path above is
      // skipped from next cycle onward.  For this current cycle, if
      // r.fire also happened, the write-back block above still runs
      // only on `r.last`; if this happens to be the last beat we
      // would pollute the cache.  Guard that here by clearing valid
      // again if that combination hits — simplest: always clear the
      // row's valid bit for the way we would have written this cycle.
      when(fence_i) {
        state_q := State.fence_drain
        // Defensive: if r.last happened to land this cycle along with
        // fence_i, the write-back block above already set valids :=
        // true.  The outer `clear_all_valids` block also fired and
        // zeroed the whole array; Chisel's last-connect rule means
        // the `:=true.B` write-back wins for that single entry.
        // Undo it here explicitly.
        tag_ram(index_reg).valids(replace_way_reg) := false.B
      }
    }

    is(State.fence_drain) {
      // Drain remaining R beats without committing any cacheline.
      // Outer `clear_all_valids` block already zeroed valids on the
      // cycle fence_i was asserted; we only need to honor the AXI
      // protocol by accepting beats until `r.last`.
      out.r.ready := true.B
      when(out.r.fire && out.r.bits.last) {
        state_q := State.idle
      }
      // A second fence_i during drain just re-clears valids (already
      // zero) — no harmful interaction.
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
