package ysyx.core.common

import chisel3._
import chisel3.util._

/** DPI-C perf-event dispatcher.
  *
  * Each active cycle (when `en === 1`) emits one increment for the given
  * event id.  The C++ side maintains a counter per id.  The id space is a
  * simple enum shared with C++ (see npc/src/cpp/perf/perf_counters.h):
  *
  * Cache / frontend pulses:
  *   0  icache_access
  *   1  icache_hit
  *   2  icache_miss
  *   3  dcache_access
  *   4  dcache_hit
  *   5  dcache_miss
  *   6  ifu_out_valid      (IFU produced an instruction this cycle)
  *   7  ifu_stall          (IFU could not deliver an instruction this cycle)
  *   8  icache_miss_cycles (counts cycles spent waiting for an ICache line fill)
  *   9  dcache_miss_cycles (counts cycles spent waiting for a DCache line fill)
  *
  * Commit-stage pulses (1 per committed instruction):
  *  10  commit             (any instruction retired this cycle)
  *  11  branch             (committed control-flow insn: BRANCH | JAL | JALR)
  *  12  branch_mispredict  (committed control-flow insn was mispredicted)
  *  13  flush              (pipeline flush triggered this cycle)
  *  14  alu                (R_ALU | I_ALU | LUI | AUIPC)
  *  15  mul_div            (R_MUL family: MUL/DIV/REM)
  *  16  load
  *  17  store
  *  18  cf_branch          (conditional BRANCH only)
  *  19  cf_jal
  *  20  cf_jalr
  *  21  csr
 *  22  system             (ECALL/EBREAK/MRET/SRET/SFENCE_VMA)
 *  23  fence              (FENCE | FENCE_I)
 *
 * Frontend pipeline events (Step E; pulsed from IFU.scala):
 *  24  fe_redirect_branch    (F3 taken-branch/jump triggered self-redirect)
 *  25  (free; formerly fe_redirect_serial — removed with fe_wait_flush)
 *  26  (free; formerly fe_wait_flush_stall — removed with fe_wait_flush)
 *  27  fe_f1_tlb_miss        (F1 had a valid VPN that missed in the iTLB)
 *  28  fe_f2_icache_wait     (F2 has a req pending but the cache isn't accepting)
 *  29  fe_fp_resp_wait       (Fp holds an inflight req still waiting on rdata)
 *
 * Using an id-based scheme keeps the RTL hook extremely small (one extmodule
 * per event source) and avoids blowing up the Verilator port count.
 */
class PerfEventHelper
    extends FixedIOExtModule(new Bundle {
      val clock = Input(Clock())
      val en = Input(Bool())
      val id = Input(UInt(8.W))
    }) {
  setInline(
    "PerfEventHelper.sv",
    """module PerfEventHelper(
      |  input        clock,
      |  input        en,
      |  input  [7:0] id
      |);
      |`ifndef SYNTHESIS
      |import "DPI-C" function void npc_perf_event(input byte id);
      |always @(posedge clock) begin
      |  if (en) npc_perf_event(id);
      |end
      |`endif
      |endmodule
      |""".stripMargin
  )
}

object PerfEvent {
  // Keep in sync with npc/src/cpp/perf/perf_counters.h::Id
  val ICACHE_ACCESS       = 0
  val ICACHE_HIT          = 1
  val ICACHE_MISS         = 2
  val DCACHE_ACCESS       = 3
  val DCACHE_HIT          = 4
  val DCACHE_MISS         = 5
  val IFU_OUT_VALID       = 6
  val IFU_STALL           = 7
  val ICACHE_MISS_CYCLES  = 8
  val DCACHE_MISS_CYCLES  = 9

  // Commit-stage events
  val COMMIT              = 10
  val BRANCH              = 11
  val BRANCH_MISPREDICT   = 12
  val FLUSH               = 13
  val COMMIT_ALU          = 14
  val COMMIT_MUL_DIV      = 15
  val COMMIT_LOAD         = 16
  val COMMIT_STORE        = 17
  val COMMIT_CF_BRANCH    = 18
  val COMMIT_CF_JAL       = 19
  val COMMIT_CF_JALR      = 20
  val COMMIT_CSR          = 21
  val COMMIT_SYSTEM       = 22
  val COMMIT_FENCE        = 23

  // Frontend pipeline events
  val FE_REDIRECT_BRANCH  = 24
  // 25, 26 are intentionally left unused (formerly fe_redirect_serial
  // and fe_wait_flush_stall; removed when the ICache began handling
  // fence.i drain itself).  Not reassigned to keep DPI-C IDs stable.
  val FE_F1_TLB_MISS      = 27
  val FE_F2_ICACHE_WAIT   = 28
  val FE_FP_RESP_WAIT     = 29

  /** Emit `pulse` cycles of the given event id.
    *
    * Must be called from within a `Module` scope. Uses the caller's
    * implicit clock automatically.
    */
  def apply(id: Int, pulse: Bool): Unit = {
    val h = Module(new PerfEventHelper)
    h.io.clock := chisel3.Module.clock
    h.io.en := pulse
    h.io.id := id.U
  }
}
