package ysyx.core.frontend

import chisel3._
import chisel3.util._

import ysyx.core.common._
import ysyx.core.sram._
import ysyx.core.mmu.{TLB, PTW}

// ============================================================
// Stage-to-stage payload bundles
// ============================================================
// These are the `bits` payloads that PipelineConnect transports between
// the IFU's pipeline stages.  The `valid` side of each handshake is
// handled by PipelineConnect's internal slot register (see
// core/common/PipelineConnect.scala).

class F1In extends NPCBundle {
  val pc = UInt(addrBits.W)
}

class F2In extends NPCBundle {
  val pc         = UInt(addrBits.W)
  val pa         = UInt(busAddrBits.W)
  val page_fault = Bool()
}

class FpIn extends NPCBundle {
  val pc = UInt(addrBits.W)
  val pa = UInt(busAddrBits.W)
}

class F3In extends NPCBundle {
  val pc        = UInt(addrBits.W)
  val inst      = UInt(dataBits.W)
  val has_fault = Bool()
  // `pa_word_sel` = pa(2): selects low vs high 32-bit word of `inst`.
  // Irrelevant on fault path (inst==0), but kept to keep F3 purely
  // combinational over `in.bits`.
  val pa_word_sel = Bool()
}

// ============================================================
// F1Stage  —  iTLB lookup + PTW slow path
// ============================================================
// Purely combinational TLB lookup on `in.bits.pc`.  On TLB miss, the
// PTW sub-FSM walks the page table over `ptw_port` and refills the
// iTLB.  `in.ready` is deasserted while a miss is being resolved.
//
// Squash semantics: `squash=1` (fe_redirect || io.flush) on any cycle
// kills the current F1 occupancy *from the outside* via the
// PipelineConnect connecting F0→F1.  Internally F1 only needs to react
// by resetting the PTW sub-FSM so that an abandoned walk's eventual
// response does not leak into a refill for a different VPN.
class F1Stage extends NPCModule {
  val io = IO(new Bundle {
    val in   = Flipped(Decoupled(new F1In))
    val out  = Decoupled(new F2In)
    val satp       = Input(UInt(dataBits.W))
    val priv       = Input(UInt(2.W))
    val sfence_vma = Input(Bool())
    val squash     = Input(Bool())
    // Per-cycle perf pulse: F1 holds a valid VPN that missed in the
    // iTLB this cycle (PTW slow path engaged or being engaged).
    val perf_tlb_miss = Output(Bool())
  })
  val ptw_port = IO(SRAMBundle(sramParams))

  val itlb = Module(new TLB(numEntries = 16))
  val ptw  = Module(new PTW)

  itlb.io.flush := io.sfence_vma
  ptw.io.satp   := io.satp
  ptw.io.priv   := io.priv
  ptw_port <> ptw.io.mem
  ptw.io.req.valid    := false.B
  ptw.io.req.bits.vpn := 0.U

  val sv39_enabled = io.satp(63, 60) === 8.U

  val in_pc  = io.in.bits.pc
  val in_vpn = in_pc(38, 12)
  val in_ofs = in_pc(11, 0)

  itlb.io.lookup.req.vpn := in_vpn

  val tlb_hit   = itlb.io.lookup.resp.hit
  val tlb_flags = itlb.io.lookup.resp.flags
  val tlb_pte_x = tlb_flags(3)
  val tlb_pa    = Cat(itlb.io.lookup.resp.ppn, in_ofs)
  val bare_pa   = in_pc(busAddrBits - 1, 0)
  val pa_sel    = Mux(sv39_enabled, tlb_pa.pad(busAddrBits), bare_pa)

  // `would_miss` / `would_fault` are computed WITHOUT gating on
  // in.valid, so the ready-side logic below does not build a
  // valid→ready cycle through PipelineConnect.  Users that need
  // in.valid (e.g. PTW startup, out.valid) still gate explicitly.
  val would_miss  = sv39_enabled && !tlb_hit
  val would_fault = sv39_enabled && tlb_hit && !tlb_pte_x
  val tlb_miss    = io.in.valid && would_miss
  val tlb_fault   = io.in.valid && would_fault

  // ----------------------------------------------------------
  // PTW sub-FSM  (unchanged from the original monolithic IFU)
  // ----------------------------------------------------------
  object PtwState extends ChiselEnum {
    val idle, req, waitResp, drain = Value
  }
  val ptw_state     = RegInit(PtwState.idle)
  val ptw_vpn_reg   = RegInit(0.U(27.W))
  val ptw_abandoned = RegInit(false.B)

  itlb.io.refill.valid := false.B
  itlb.io.refill.vpn   := ptw_vpn_reg
  itlb.io.refill.ppn   := ptw.io.resp.bits.ppn
  itlb.io.refill.flags := ptw.io.resp.bits.flags

  switch(ptw_state) {
    is(PtwState.idle) {
      when(io.in.valid && tlb_miss) {
        ptw_state     := PtwState.req
        ptw_abandoned := false.B
      }
    }
    is(PtwState.req) {
      ptw.io.req.valid    := true.B
      ptw.io.req.bits.vpn := in_vpn
      when(ptw.io.req.fire) {
        ptw_vpn_reg := in_vpn
        ptw_state   := PtwState.waitResp
      }
    }
    is(PtwState.waitResp) {
      when(ptw.io.resp.valid) {
        val do_refill = !ptw_abandoned && !ptw.io.resp.bits.fault &&
                        io.in.valid && (in_vpn === ptw_vpn_reg)
        when(do_refill) {
          itlb.io.refill.valid := true.B
        }
        ptw_state := PtwState.idle
      }
    }
    is(PtwState.drain) {
      when(ptw.io.resp.valid) {
        ptw_state := PtwState.idle
      }
    }
  }

  val ptw_fault_pulse =
    (ptw_state === PtwState.waitResp) && ptw.io.resp.valid &&
    ptw.io.resp.bits.fault && !ptw_abandoned &&
    io.in.valid && (in_vpn === ptw_vpn_reg)

  val resolved = io.in.valid && !tlb_miss

  io.out.valid          := resolved || ptw_fault_pulse
  io.out.bits.pc         := in_pc
  io.out.bits.pa         := Mux(ptw_fault_pulse, 0.U, pa_sel)
  io.out.bits.page_fault := ptw_fault_pulse || tlb_fault
  // `in.ready` uses the same SkidBuffer-style "empty OR fire" form
  // used by F2Stage: an empty slot must default to ready=1 so that
  // the upstream PipelineConnect can deposit the first entry.  Once
  // the slot is full, release requires either the downstream accept
  // (out.ready) without a TLB miss, or the one-cycle PTW fault
  // pulse.
  io.in.ready := !io.in.valid ||
                 (io.out.ready && (!would_miss || ptw_fault_pulse))

  // Squash path: if the caller redirects the front-end while PTW is
  // still walking, mark the walk abandoned and drain its response.
  val ptw_going_inflight = (ptw_state === PtwState.req) && ptw.io.req.fire
  val ptw_staying_wait   = (ptw_state === PtwState.waitResp) && !ptw.io.resp.valid
  val ptw_becomes_inflight_next = ptw_going_inflight || ptw_staying_wait
  when(io.squash) {
    when(ptw_becomes_inflight_next) {
      ptw_abandoned := true.B
      ptw_state     := PtwState.drain
    }.elsewhen(ptw_state =/= PtwState.drain) {
      ptw_state := PtwState.idle
    }
  }

  io.perf_tlb_miss := tlb_miss
}

// ============================================================
// F2Stage  —  ICache request driver + fault bypass
// ============================================================
// F2 is purely combinational over `in.bits`.  Its job is:
//   - Drive icache.req/addr/size when `in` holds a non-fault entry and
//     the downstream Fp slot can accept (signalled by `fp_ready`).
//   - For page-fault entries, bypass the cache and hand straight to F3
//     via the `out_fault` port.
// `in.ready` fires on whichever exit path is taken this cycle.
class F2Stage extends NPCModule {
  val io = IO(new Bundle {
    val in        = Flipped(Decoupled(new F2In))
    val out_cache = Decoupled(new FpIn)
    val out_fault = Decoupled(new F3In)
    // Fp can absorb a new cache request this cycle.
    val fp_ready_for_new = Input(Bool())
    // Fp is currently empty (needed for the fault-bypass path: we must
    // never race a fault entry against an inflight cache response
    // that is about to land on F3).
    val fp_empty = Input(Bool())
    // Per-cycle perf pulse: F2 has a cache request pending that has
    // not yet been accepted (cache busy or Fp can't absorb).
    val perf_icache_wait = Output(Bool())
  })
  val icache = IO(SRAMBundle(sramParams))

  // `would_fault` / `would_cache` are computed WITHOUT gating on
  // in.valid so that io.in.ready below does not form a
  // valid→ready combinational cycle through PipelineConnect.
  val would_fault = io.in.bits.page_fault
  val aligned_pa = Cat(
    io.in.bits.pa(busAddrBits - 1, dataBytesBits),
    0.U(dataBytesBits.W)
  )

  // ICache request: drive req whenever we have a valid non-fault
  // entry AND Fp has room for a new in-flight slot.
  val req_asserted = io.in.valid && !would_fault && io.fp_ready_for_new

  icache.req   := req_asserted
  icache.wen   := false.B
  icache.size  := dataBytesBits.U
  icache.addr  := aligned_pa
  icache.wstrb := 0.U
  icache.wdata := 0.U

  // Cache path: in→Fp whenever the cache is accepting the req this
  // cycle.  `io.out_cache.valid` must be gated on `io.in.valid` so
  // downstream doesn't latch garbage.
  io.out_cache.valid      := req_asserted && icache.ack
  io.out_cache.bits.pc    := io.in.bits.pc
  io.out_cache.bits.pa    := io.in.bits.pa

  // Fault path: in→F3 directly.  Fp must be empty (not just drainable)
  // so we never race a fault entry against an inflight cache response
  // that is about to land on F3 via the Fp→F3 PipelineConnect.
  io.out_fault.valid             := io.in.valid && would_fault && io.fp_empty
  io.out_fault.bits.pc           := io.in.bits.pc
  io.out_fault.bits.inst         := 0.U
  io.out_fault.bits.has_fault    := true.B
  io.out_fault.bits.pa_word_sel  := io.in.bits.pa(2)

  // `in.ready` must not gate the ICache's ack-path on `in.valid`,
  // because `icache.ack` is itself a combinational function of
  // `icache.req` which is already `in.valid`-gated — leaving the slot
  // stuck at ready=0 whenever it's empty.  Instead, declare the slot
  // "ready to release" whenever it is empty (slot=empty ⇒ ready=1) OR
  // the fire condition holds for its current bits.  This is the
  // textbook SkidBuffer-style ready signal.
  val fire_fault_local = would_fault && io.out_fault.ready && io.fp_empty
  val fire_cache_local = !would_fault && io.fp_ready_for_new &&
                         icache.ack && io.out_cache.ready
  io.in.ready := !io.in.valid || fire_fault_local || fire_cache_local

  io.perf_icache_wait := io.in.valid && !would_fault &&
                        (!io.fp_ready_for_new || !icache.ack)
}

// ============================================================
// FpStage  —  ICache in-flight FIFO (depth-4, circular buffer)
// ============================================================
// Fp is a small FIFO that decouples "cache request submitted" (enq,
// driven by F2's req+ack on the cache) from "instruction delivered to
// F3" (deq).  Each slot holds a meta entry (pc, pa) and, once the
// corresponding ICache response arrives, a latched `inst` word.
//
// Because the ICache is strictly serial — it only asserts `ack` in
// states where it can accept a new request, and delivers exactly one
// `done` per accepted req — the entries in this FIFO complete
// strictly in enqueue order.  A single `rdata_ptr` register therefore
// suffices to identify which slot should absorb the next `icache_done`
// pulse.
//
// Handshake summary:
//   in  : enq;  valid = F2 cache-req accepted this cycle, ready = !full.
//   out : deq;  valid = head slot has inst, ready = F3 can accept.
//
// Squash:
//   `squash = 1` synchronously clears all valid bits and the pointers;
//   any outstanding cache responses are silently dropped (the ICache
//   itself completes its own FSM without needing a consumer).
class FpStage(val depth: Int = 4) extends NPCModule {
  require(depth >= 1 && isPow2(depth),
          s"FpStage depth must be a positive power of two (got $depth)")

  val io = IO(new Bundle {
    val in     = Flipped(Decoupled(new FpIn))
    val out    = Decoupled(new F3In)
    val squash = Input(Bool())
    // ICache response side-channel.  A `done` pulse writes `rdata`
    // into the oldest not-yet-filled slot (pointed at by rdata_ptr).
    val icache_done  = Input(Bool())
    val icache_rdata = Input(UInt(dataBits.W))
    // Exported for F2 so it can decide whether to issue icache.req.
    val ready_for_new = Output(Bool())
    // Exported for F2 fault-bypass: "nothing at all in flight".
    val empty         = Output(Bool())
    // Exported for debug/perf: "there is ≥1 entry waiting on rdata
    // this cycle and icache is not responding".
    val perf_resp_wait = Output(Bool())
  })

  // --- Storage ---
  class Slot extends Bundle {
    val pc         = UInt(addrBits.W)
    val pa         = UInt(busAddrBits.W)
    val inst       = UInt(dataBits.W)
    val inst_ready = Bool()
  }
  val slots       = Reg(Vec(depth, new Slot))
  val valids      = RegInit(VecInit(Seq.fill(depth)(false.B)))

  // Circular pointers (log2Ceil(depth) + 1 bits to distinguish full vs
  // empty by toggling a wrap bit in the MSB — classic FIFO trick).
  val ptrW        = log2Ceil(depth) + 1
  val enq_ptr     = RegInit(0.U(ptrW.W))
  val deq_ptr     = RegInit(0.U(ptrW.W))
  val rdata_ptr   = RegInit(0.U(ptrW.W))  // next slot to absorb icache_done

  def idx(p: UInt) = p(ptrW - 2, 0)

  val full  = (enq_ptr(ptrW - 1) =/= deq_ptr(ptrW - 1)) &&
              (idx(enq_ptr) === idx(deq_ptr))
  val empty = enq_ptr === deq_ptr
  val has_inflight = rdata_ptr =/= enq_ptr  // at least one slot awaiting rdata

  io.empty         := empty
  io.ready_for_new := !full

  // --- Enq (from F2) ---
  io.in.ready := !full
  val do_enq = io.in.fire

  // --- Deq (to F3) ---
  val head = slots(idx(deq_ptr))
  io.out.valid            := !empty && head.inst_ready
  io.out.bits.pc          := head.pc
  io.out.bits.inst        := head.inst
  io.out.bits.has_fault   := false.B
  io.out.bits.pa_word_sel := head.pa(2)
  val do_deq = io.out.fire

  // --- rdata back-fill ---
  // The ICache serialises its responses: the k-th `done` pulse we see
  // after reset corresponds to the k-th accepted request, regardless
  // of how much F3 back-pressure there has been.  So `rdata_ptr` walks
  // through the slots in strict FIFO order, one step per `icache_done`.
  val do_rdata = io.icache_done && has_inflight

  // --- Slot updates ---
  //   enq       : valids(enq_idx)        := true;  fill pc/pa
  //   rdata     : slots(rdata_idx).inst  := rdata; inst_ready := true
  //   deq       : valids(deq_idx)        := false
  //   squash    : clear everything (last-connect wins)
  val enq_idx   = idx(enq_ptr)
  val rdata_idx = idx(rdata_ptr)
  val deq_idx   = idx(deq_ptr)

  when(do_enq) {
    slots(enq_idx).pc         := io.in.bits.pc
    slots(enq_idx).pa         := io.in.bits.pa
    slots(enq_idx).inst       := 0.U
    slots(enq_idx).inst_ready := false.B
    valids(enq_idx)           := true.B
    enq_ptr                   := enq_ptr + 1.U
  }

  when(do_rdata) {
    slots(rdata_idx).inst       := io.icache_rdata
    slots(rdata_idx).inst_ready := true.B
    rdata_ptr                   := rdata_ptr + 1.U
  }

  when(do_deq) {
    valids(deq_idx) := false.B
    deq_ptr         := deq_ptr + 1.U
  }

  when(io.squash) {
    for (i <- 0 until depth) {
      valids(i) := false.B
    }
    enq_ptr   := 0.U
    deq_ptr   := 0.U
    rdata_ptr := 0.U
  }

  io.perf_resp_wait := has_inflight && !io.icache_done
}

// ============================================================
// F3Stage  —  Predict + handoff to back-end
// ============================================================
// Pure combinational wrapper over `in.bits`.  Drives the Predict port
// and computes `fe_take_branch` for the top-level fe_redirect
// generator.  The only sequential element is the output Irrevocable
// handshake back-pressure, which is handled entirely by the upstream
// PipelineConnect.
//
// Note on fence.i:
//   The front-end is oblivious to fence.i.  The instruction is passed
//   down to the back-end like any other; when CommitStage retires it,
//   `fence_i` pulses into the ICache (which handles in-flight refills
//   via its `fence_drain` state) and `io.flush` squashes all frontend
//   slots.  Therefore F3 does NOT need to recognise fence.i here.
class F3Stage extends NPCModule {
  val io = IO(new Bundle {
    val in      = Flipped(Decoupled(new F3In))
    val out     = Irrevocable(new IFUOutput)
    val predict = Flipped(new PredictBundle)
    // Output for the IFU top to drive fe_redirect.
    val fe_take_branch = Output(Bool())
  })

  // The F3In.inst field carries the full 64-bit doubleword fetched
  // from the cache; we pick the low/high 32-bit instruction word.
  val inst_word = Mux(io.in.bits.pa_word_sel,
                      io.in.bits.inst(63, 32),
                      io.in.bits.inst(31, 0))
  val inst_bits = Mux(io.in.bits.has_fault, 0.U(instBits.W), inst_word)

  io.predict.pc     := io.in.bits.pc
  io.predict.inst   := inst_bits
  io.predict.commit := io.out.fire

  io.out.valid            := io.in.valid
  io.out.bits.inst        := inst_bits
  io.out.bits.pc          := io.in.bits.pc
  io.out.bits.predict_npc := io.predict.dnpc
  io.out.bits.ghr         := io.predict.ghr
  io.out.bits.mcause      := Mux(io.in.bits.has_fault, 12.U, 0.U)
  io.out.bits.mtval       := Mux(io.in.bits.has_fault, io.in.bits.pc, 0.U)
  io.out.bits.has_except  := io.in.bits.has_fault

  io.in.ready := io.out.ready

  // Front-end self-redirect predicate (sampled by IFU top to form
  // fe_redirect).  Only asserted on the cycle of io.out.fire so the
  // downstream logic can assume "one pulse per triggering instruction".
  //
  // All serializing instructions (fence / fence.i / ecall / ebreak /
  // mret / sret / sfence.vma / csrw) are handled by the back-end:
  //   - fence.i            : CommitStage pulses `fence_i` to the ICache,
  //                          which clears valids and (if a refill is in
  //                          flight) enters `fence_drain` to complete the
  //                          AXI burst without polluting the array.  The
  //                          same cycle io.flush squashes all frontend
  //                          slots and reloads pc_q with pc+4.
  //   - ecall / ebreak     : trap -> redirect -> flush.
  //   - mret / sret        : PC := mepc/sepc, flush asserts on xret.
  //   - sfence.vma         : head_is_sfence in CommitStage forces flush.
  //   - csrrw/csrrs/csrrc  : head_is_csrw in CommitStage forces flush
  //     (writes only)        (csr reads do not flush).
  val snpc = io.in.bits.pc + 4.U
  io.fe_take_branch := io.out.fire && !io.in.bits.has_fault &&
                       (io.predict.dnpc =/= snpc)
}
