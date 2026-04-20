package ysyx.core.frontend

import chisel3._
import chisel3.util._

import freechips.rocketchip.amba.axi4._
import freechips.rocketchip.util._

import ysyx.core.common._
import ysyx.core.common.PerfEvent
import ysyx.core.lsu._
import ysyx.core.sram._
import ysyx.core.backend.InstType
import ysyx.core.backend.{BruRobEntry, JalRobEntry, JalrRobEntry, MretRobEntry, ExceptRobEntry}

class IFUOutput extends NPCBundle {
  val inst = UInt(instBits.W)
  val pc = UInt(addrBits.W)
  val predict_npc = UInt(addrBits.W)
  val ghr = UInt(ghrBits.W)
  val mcause = UInt(dataBits.W)
  val mtval = UInt(dataBits.W)
  val has_except = Bool()
}

class RedirectBundle extends NPCBundle {
  val wrong_pc = UInt(addrBits.W)
  val inst_type = InstType()
  val mispredict = Bool()
  val bru = new BruRobEntry
  val jal = new JalRobEntry
  val jalr = new JalrRobEntry
  val ghr = UInt(ghrBits.W)
  val dnpc = UInt(addrBits.W)
}

class PredictBundle extends NPCBundle {
  val dnpc = UInt(addrBits.W)
  val ghr = UInt(ghrBits.W)
  val pc = Input(UInt(addrBits.W))
  val inst = Input(UInt(instBits.W))
  // `commit=1` exactly on the cycle F3 hands off an instruction to the
  // back-end.  Gates RAS push/pop so that speculative / squashed F3
  // cycles cannot pollute the stack.
  val commit = Input(Bool())
}

/** Pipelined instruction-fetch unit — explicit submodule + PipelineConnect form.
  *
  * Pipeline topology:
  *
  *   pc_q --+-- F0Out -->[PC]----> F1Stage --+-- F2In --->[F1→F2]----> F2Stage --+-- FpIn  -->[F2→Fp]----> FpStage --+
  *          |                                                                    +-- F3In  -->[F2→F3 fault bypass] -+
  *          |                                                                                                        |
  *          +---------- speculative pc+4 on F0 fire -----------+                                                     v
  *                                                                                                   (Fp→F3 handoff or F2-fault handoff)
  *                                                                                                                  |
  *                                                                                                                  v
  *                                                                                                              F3Stage -> io.out
  *
  * Stage roles:
  *   F0  (in IFU top) :  pc_q + speculative +4.
  *   F1  (F1Stage)    :  iTLB lookup; PTW slow-path sub-FSM.
  *   F2  (F2Stage)    :  ICache request driver; fault bypass.
  *   Fp  (FpStage)    :  cache in-flight buffer (rdata latch).
  *   F3  (F3Stage)    :  predict + handoff + fe_redirect decision.
  *
  * Inter-stage registers are owned by the `PipelineConnect` objects.
  * Each PipelineConnect's `flush` argument is the per-stage squash
  * signal — `fe_redirect || io.flush` for every boundary here, since
  * both squash vectors apply to every slot prior to and including F3.
  *
  * Front-end self-redirect semantics:
  *
  *   fe_take_branch      : F3 hands off a CF whose predicted dnpc
  *                         != pc+4.  Squash F0/F1/F2/Fp/F3 and reload
  *                         pc_q from predict.dnpc.
  *   io.flush            : back-end mispredict / trap / xret / csrw /
  *                         sfence.vma / fence.i; drops everything and
  *                         reloads pc_q from io.dnpc.
  *
  * The front-end is fully oblivious to serializing instructions
  * (fence, fence.i, ecall, ebreak, mret, sret, sfence.vma, csrw).
  * They are handled entirely by the back-end's io.flush path.  For
  * fence.i in particular, the ICache itself cleans up in-flight
  * refills via its `fence_drain` state, so there is no window in
  * which a pre-fence.i speculative refill could pollute the cache.
  *
  * Throughput note: standard PipelineConnect inserts one register per
  * boundary, so taken branches pay +1 extra bubble cycle compared to
  * the old hand-written 4-stage pipeline.  Straight-line 1-IPC
  * streaming is preserved because back-to-back hits still advance each
  * stage every cycle.
  */
class IFU extends NPCModule {

  val io = IO(new Bundle {
    val out = Irrevocable(new IFUOutput)
    val predict = Flipped(new PredictBundle)
    val flush = Input(Bool())
    val dnpc = Input(UInt(dataBits.W))
    val satp = Input(UInt(dataBits.W))
    val priv = Input(UInt(2.W))
    val sfence_vma = Input(Bool())
  })

  val icache = IO(SRAMBundle(sramParams))
  val ptw_port = IO(SRAMBundle(sramParams))

  // ============================================================
  // F0 state
  // ============================================================
  val pc_q = RegInit(ysyx.SoCConfig.resetVector.U(addrBits.W))

  // ============================================================
  // Submodule instantiations
  // ============================================================
  val f1 = Module(new F1Stage)
  val f2 = Module(new F2Stage)
  val fp = Module(new FpStage)
  val f3 = Module(new F3Stage)

  // External wiring that doesn't pass through PipelineConnect.
  f1.io.satp       := io.satp
  f1.io.priv       := io.priv
  f1.io.sfence_vma := io.sfence_vma
  ptw_port <> f1.ptw_port

  icache <> f2.icache

  fp.io.icache_done  := icache.done
  fp.io.icache_rdata := icache.rdata

  f3.io.predict <> io.predict
  io.out <> f3.io.out

  // Cross-stage side signals (Fp→F2 handshake hints).
  f2.io.fp_ready_for_new := fp.io.ready_for_new
  f2.io.fp_empty         := fp.io.empty

  // ============================================================
  // F3 self-redirect signal generation (pulled out for legibility).
  //   These are driven by F3Stage purely from its own in.bits +
  //   io.predict.dnpc + io.out.fire, so they are safe to use in the
  //   flush argument of every PipelineConnect upstream of F3 (the
  //   ready/valid chain has already been broken by F3.in's slot).
  // ============================================================
  val fe_take_branch = f3.io.fe_take_branch
  val fe_redirect    = fe_take_branch
  val squash         = fe_redirect || io.flush

  // Internal-state squash for submodules that hold Regs outside of
  // PipelineConnect slots (F1's PTW FSM, Fp's inst-latch).
  f1.io.squash := squash
  fp.io.squash := squash

  // ============================================================
  // F0 → F1  via PipelineConnect
  // ============================================================
  // F0 is always offering the current pc_q; squashes and io.flush are
  // handled via the `squash` signal and pc_q rewrites below.
  val f0_out = Wire(Decoupled(new F1In))
  f0_out.valid   := true.B
  f0_out.bits.pc := pc_q

  PipelineConnect(f0_out, f1.io.in, squash)

  // ============================================================
  // F1 → F2  via PipelineConnect
  // ============================================================
  PipelineConnect(f1.io.out, f2.io.in, squash)

  // ============================================================
  // F2 → Fp  (direct connection, NO PipelineConnect!)
  //
  // A PipelineConnect here would insert a 1-cycle slot register between
  // F2's "cache request accepted" event and Fp's "request is in flight"
  // state.  That register delays Fp by one cycle, which breaks an
  // invariant of the AXI4 ICache: the cache drives `done=1` with valid
  // `rdata` on the cycle immediately after `req && ack`, and Fp must
  // already be valid that cycle to latch it.  A slot register makes Fp
  // miss the very response it was created to capture.
  //
  // Instead, F2 (purely combinational) and Fp (stateful) together form
  // a single logical pipeline stage whose two halves are glued by the
  // ICache's own handshake.  Squash is conveyed via `fp.io.squash`
  // (already wired above), which clears Fp's internal state regardless
  // of what F2 tried to push this cycle.
  // ============================================================
  fp.io.in.valid       := f2.io.out_cache.valid
  fp.io.in.bits        := f2.io.out_cache.bits
  f2.io.out_cache.ready := fp.io.in.ready

  // ============================================================
  // Fp / F2-fault → F3  via a merged Decoupled source + PipelineConnect
  //   Fault entries bypass Fp entirely.  Because F2 only asserts
  //   out_fault.valid when `fp_empty=1`, Fp cannot produce an out.valid
  //   in the same cycle; the mux below is a clean disjoint-valid
  //   select.  We give fault priority nonetheless — it's cheaper than
  //   proving the disjointness structurally.
  // ============================================================
  val f3_src = Wire(Decoupled(new F3In))
  val fault_valid = f2.io.out_fault.valid
  f3_src.valid := fault_valid || fp.io.out.valid
  f3_src.bits  := Mux(fault_valid, f2.io.out_fault.bits, fp.io.out.bits)

  f2.io.out_fault.ready := f3_src.ready
  fp.io.out.ready       := f3_src.ready && !fault_valid

  PipelineConnect(f3_src, f3.io.in, squash)

  // ============================================================
  // F0 register updates (speculative pc+4, redirect, flush reload)
  //
  //   Priority (last-connect wins in Chisel):
  //     1. f0_out.fire          : pc_q += 4   (speculative)
  //     2. fe_redirect          : on taken branch, pc_q := predict.dnpc.
  //     3. io.flush             : pc_q := io.dnpc.
  // ============================================================
  when(f0_out.fire) {
    pc_q := pc_q + 4.U
  }

  when(fe_take_branch) {
    pc_q := io.predict.dnpc
  }

  when(io.flush) {
    pc_q := io.dnpc
  }

  // ============================================================
  // Perf events
  // ============================================================
  PerfEvent(PerfEvent.IFU_OUT_VALID, io.out.fire)
  PerfEvent(PerfEvent.IFU_STALL,     !io.out.valid)

  // Front-end self-redirect cause.
  PerfEvent(PerfEvent.FE_REDIRECT_BRANCH, fe_take_branch)

  // Per-cycle stall pulses.
  //   fe_f1_tlb_miss      : F1 is holding a VPN that missed in iTLB.
  //   fe_f2_icache_wait   : F2 has a cache req pending but not yet
  //                         accepted (cache busy or Fp can't absorb).
  //   fe_fp_resp_wait     : Fp holds an inflight request still waiting
  //                         on rdata.
  PerfEvent(PerfEvent.FE_F1_TLB_MISS,    f1.io.perf_tlb_miss)
  PerfEvent(PerfEvent.FE_F2_ICACHE_WAIT, f2.io.perf_icache_wait)
  PerfEvent(PerfEvent.FE_FP_RESP_WAIT,   fp.io.perf_resp_wait)
}
