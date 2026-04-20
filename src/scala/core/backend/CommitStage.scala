package ysyx.core.backend

import chisel3._
import chisel3.util._

import ysyx.core.common._
import ysyx.core.frontend._

class DebugCommitBundle extends NPCBundle {
  val pc = UInt(dataBits.W)
  val dnpc = UInt(dataBits.W)
  val inst = UInt(instBits.W)
  val is_mmio = Bool()
}

class CommitStage extends NPCModule {
  // ArchRAT write
  val arch_rat_w = IO(Valid(new ArchRATWritePort))
  // FreeList: release old_prd (also serves as commit_alloc)
  val freelist_free = IO(Valid(UInt(NRPhyRegBits.W)))

  val csr = IO(new Bundle {
    val trap = Valid(new CsrTrapWritePort)
    val mret_event = Output(Bool())
    val sret_event = Output(Bool())
    val sfence_vma = Output(Bool())
    val mepc = Input(UInt(dataBits.W))
    val sepc = Input(UInt(dataBits.W))
    val trap_target = Input(UInt(dataBits.W))
    val priv = Input(UInt(2.W))
    val interrupt_pending = Input(Bool())
    val interrupt_cause = Input(UInt(dataBits.W))
    val interrupt_target = Input(UInt(dataBits.W))
  })
  val rob = IO(Flipped(ReqDone(new CommitBundle)))
  val redirect = IO(Valid(new RedirectBundle))
  val flush = IO(Bool())
  val fence_i = IO(Bool())

  // ---- Head entry aliases ----
  val head_entry = rob.bits.entry
  val head_tag = rob.bits.tag
  val head_valid = rob.req

  val head_is_mem = head_entry.mem.r_en || head_entry.mem.w_en
  val head_is_csr = head_entry.inst_type === InstType.CSR
  val head_is_mret = head_entry.inst_type === InstType.MRET
  val head_is_sret = head_entry.inst_type === InstType.SRET
  val head_is_sfence = head_entry.inst_type === InstType.SFENCE_VMA
  val head_is_ecall = head_entry.inst_type === InstType.ECALL
  val head_is_fence = head_entry.inst_type === InstType.FENCE
  val head_is_fence_i = head_entry.inst_type === InstType.FENCE_I

  // ---- Defaults ----
  rob.done := head_valid

  // ArchRAT
  arch_rat_w.valid := false.B
  arch_rat_w.bits.addr := head_entry.rd.arch_rd
  arch_rat_w.bits.preg := head_entry.rd.new_prd

  // FreeList
  freelist_free.valid := false.B
  freelist_free.bits := head_entry.rd.old_prd

  // Trap defaults
  csr.trap.valid := false.B
  csr.trap.bits.xepc := head_entry.pc
  csr.trap.bits.xcause := head_entry.except.mcause
  csr.trap.bits.xtval := head_entry.except.mtval
  csr.trap.bits.is_interrupt := false.B

  csr.mret_event := false.B
  csr.sret_event := false.B
  csr.sfence_vma := false.B

  fence_i := false.B

  val dbg_is_mmio = WireDefault(false.B)

  // ---- Compute ECALL cause based on current privilege ----
  val ecall_cause = MuxLookup(csr.priv, 11.U)(Seq(
    PRV_U.U -> 8.U,
    PRV_S.U -> 9.U,
    PRV_M.U -> 11.U
  ))

  // ---- Determine actual exception cause (privilege-aware ECALL) ----
  val actual_xcause = Mux(head_is_ecall, ecall_cause, head_entry.except.mcause)

  // ---- Check for interrupt injection ----
  // Inject interrupt when: head instruction commits normally AND interrupt is pending
  // The interrupt is taken *after* the current instruction retires
  val take_interrupt = head_valid && !head_entry.except.valid && csr.interrupt_pending &&
    !head_is_mret && !head_is_sret && !head_is_sfence && !head_is_fence_i

  // Compute the "next PC" of the retiring instruction for interrupt epc
  val retiring_dnpc = MuxCase(
    head_entry.predict_npc,
    Seq(
      (head_entry.inst_type === InstType.JAL) -> head_entry.jal.dnpc,
      (head_entry.inst_type === InstType.JALR) -> head_entry.jalr.dnpc,
      (head_entry.inst_type === InstType.BRANCH) -> Mux(
        head_entry.bru.br_flag,
        head_entry.bru.dnpc,
        head_entry.bru.snpc
      )
    )
  )

  // ---- Redirect ----
  redirect.valid := rob.fire
  redirect.bits.wrong_pc := head_entry.pc
  redirect.bits.inst_type := head_entry.inst_type
  redirect.bits.jal := head_entry.jal
  redirect.bits.jalr := head_entry.jalr
  redirect.bits.bru := head_entry.bru
  redirect.bits.ghr := head_entry.ghr
  redirect.bits.dnpc := MuxCase(
    head_entry.predict_npc,
    Seq(
      take_interrupt -> csr.interrupt_target,
      (head_entry.except.valid) -> csr.trap_target,
      (head_is_mret) -> csr.mepc,
      (head_is_sret) -> csr.sepc,
      (head_entry.inst_type === InstType.JAL) -> head_entry.jal.dnpc,
      (head_entry.inst_type === InstType.JALR) -> head_entry.jalr.dnpc,
      (head_entry.inst_type === InstType.BRANCH) -> Mux(
        head_entry.bru.br_flag,
        head_entry.bru.dnpc,
        head_entry.bru.snpc
      )
    )
  )
  val is_diff = (redirect.bits.dnpc =/= head_entry.predict_npc)
  redirect.bits.mispredict := false.B
  // Must also flush on sfence.vma: the frontend may have speculatively
  // fetched past a csrw satp / sfence.vma under the OLD satp, producing
  // stale PTW translations and stale inst_q contents.  Flushing ensures the
  // next instruction (and its jalr/branch targets) get re-fetched under the
  // new satp with a fresh TLB.
  flush := rob.fire && (is_diff || head_is_fence_i || head_is_sfence)

  // ---- Commit logic ----
  when(head_valid) {
    when(take_interrupt) {
      // Normal retire + inject interrupt trap
      when(head_entry.rd.rd_wen) {
        arch_rat_w.valid := true.B
        freelist_free.valid := true.B
      }
      dbg_is_mmio := head_is_mem && head_entry.is_mmio
      csr.trap.valid := true.B
      csr.trap.bits.is_interrupt := true.B
      csr.trap.bits.xcause := csr.interrupt_cause
      csr.trap.bits.xepc := retiring_dnpc
      csr.trap.bits.xtval := 0.U
    }.elsewhen(head_entry.except.valid) {
      csr.trap.valid := true.B
      csr.trap.bits.xcause := actual_xcause
    }.elsewhen(head_is_mret) {
      csr.mret_event := true.B
    }.elsewhen(head_is_sret) {
      csr.sret_event := true.B
    }.elsewhen(head_is_sfence) {
      csr.sfence_vma := true.B
    }.elsewhen(head_is_fence_i) {
      fence_i := true.B
    }.otherwise {
      when(head_entry.rd.rd_wen) {
        arch_rat_w.valid := true.B
        freelist_free.valid := true.B
      }
      dbg_is_mmio := head_is_mem && head_entry.is_mmio
      redirect.bits.mispredict := is_diff
    }
  }

  // sequential sync: delay 1 cycle
  val probe = IO(Valid(new DebugCommitBundle))
  probe.valid := RegNext(rob.fire)
  probe.bits.pc := RegNext(head_entry.pc)
  probe.bits.dnpc := RegNext(redirect.bits.dnpc)
  probe.bits.inst := RegNext(head_entry.inst)
  probe.bits.is_mmio := RegNext(dbg_is_mmio)

  // ---- Perf counters (DPI-C event pulses) ----
  // No RTL registers: every event is dispatched via PerfEvent() to the C++
  // side, which maintains the accumulators. `commit_pulse` guards the
  // per-class classification so mispredicts/flushes on non-committing
  // cycles never leak through.
  val commit_pulse = rob.fire
  val it = head_entry.inst_type
  val is_cf = it === InstType.BRANCH || it === InstType.JAL || it === InstType.JALR

  PerfEvent(PerfEvent.COMMIT,              commit_pulse)
  PerfEvent(PerfEvent.BRANCH,              commit_pulse && is_cf)
  PerfEvent(PerfEvent.BRANCH_MISPREDICT,   commit_pulse && is_cf && redirect.bits.mispredict)
  // `flush` already implies rob.fire (see its definition above), so no
  // additional guard is needed.
  PerfEvent(PerfEvent.FLUSH,               flush)

  val is_alu = it === InstType.R_ALU || it === InstType.I_ALU ||
               it === InstType.LUI   || it === InstType.AUIPC
  val is_system = it === InstType.ECALL || it === InstType.EBREAK ||
                  it === InstType.MRET  || it === InstType.SRET   ||
                  it === InstType.SFENCE_VMA
  val is_fence = it === InstType.FENCE || it === InstType.FENCE_I

  PerfEvent(PerfEvent.COMMIT_ALU,       commit_pulse && is_alu)
  PerfEvent(PerfEvent.COMMIT_MUL_DIV,   commit_pulse && (it === InstType.R_MUL))
  PerfEvent(PerfEvent.COMMIT_LOAD,      commit_pulse && (it === InstType.LOAD))
  PerfEvent(PerfEvent.COMMIT_STORE,     commit_pulse && (it === InstType.STORE))
  PerfEvent(PerfEvent.COMMIT_CF_BRANCH, commit_pulse && (it === InstType.BRANCH))
  PerfEvent(PerfEvent.COMMIT_CF_JAL,    commit_pulse && (it === InstType.JAL))
  PerfEvent(PerfEvent.COMMIT_CF_JALR,   commit_pulse && (it === InstType.JALR))
  PerfEvent(PerfEvent.COMMIT_CSR,       commit_pulse && (it === InstType.CSR))
  PerfEvent(PerfEvent.COMMIT_SYSTEM,    commit_pulse && is_system)
  PerfEvent(PerfEvent.COMMIT_FENCE,     commit_pulse && is_fence)
}
