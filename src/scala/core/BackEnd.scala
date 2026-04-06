package ysyx.core.backend

import chisel3._
import chisel3.util._

import freechips.rocketchip.amba.axi4._
import freechips.rocketchip.util._

import ysyx.core.common._
import ysyx.core.frontend._
import ysyx.core.lsu._
import ysyx.core.DebugBundle
import ysyx.core.sram._

class BackEnd extends NPCModule {

  val io = IO(new Bundle {
    val in = Flipped(Decoupled(new IFUOutput))
    val redirect = Output(Valid(new RedirectBundle))
    val flush = Output(Bool())
  })

  val dcache = IO(SRAMBundle(sramParams))
  val perip = IO(SRAMBundle(sramParams))
  val interrupt = IO(Input(Bool()))
  val fence_i = IO(Output(Bool()))
  val probe = IO(Valid(new DebugBundle))

  // ==========================================================
  // Sub-modules
  // ==========================================================
  val decodeStage_ = Module(new DecodeStage)
  val renameStage_ = Module(new RenameStage)
  val dispatcher_ = Module(new Dispatcher)
  val commitStage_ = Module(new CommitStage)

  val prf_ = Module(new PRF(numReadPorts = 7, numWritePorts = 4))
  val freeList_ = Module(new FreeList)
  val busyTable_ = Module(new BusyTable(numReadPorts = 4, numWakeupPorts = 3))
  val futureRat_ = Module(new FutureRAT(numReadPorts = 3))
  val archRat_ = Module(new ArchRAT)
  val rob_ = Module(new Rob)
  val alu_ = Module(new ALU)
  val bru_ = Module(new BRU)
  val alu_iq_ = Module(new ALUIssueQueue)
  val bru_iq_ = Module(new BRUIssueQueue)
  val lsu_ = Module(new LSU)
  val csru_ = Module(new CSRU)

  // ==========================================================
  // AXI — dcache + perip pass-through to LSU
  // ==========================================================
  lsu_.dcache <> dcache
  lsu_.perip <> perip

  // ==========================================================
  // Flush / redirect wiring
  // ==========================================================
  val flush = commitStage_.flush

  io.flush := flush
  io.redirect := commitStage_.redirect

  rob_.io.flush := flush
  freeList_.io.flush := flush
  busyTable_.io.flush := flush
  futureRat_.io.flush := flush
  alu_iq_.io.flush := flush
  bru_iq_.io.flush := flush

  // Flush recovery: rebuild from ArchRAT snapshot (with write forwarding)
  futureRat_.io.arch_snapshot := archRat_.io.snapshot
  freeList_.io.arch_snapshot := archRat_.io.snapshot

  // ==========================================================
  // Wakeup buses (3 sources)
  // ==========================================================
  // wakeup0: ALU execution writeback
  val wakeup_alu = Wire(Valid(new WakeupPort))
  // wakeup1: dispatch-resolved (JAL/JALR/LUI/AUIPC)
  val wakeup_disp = dispatcher_.io.wakeup
  // wakeup2: late execution writeback (load/CSR, directly from ROB)
  val wakeup_late_lsu = Wire(Valid(new WakeupPort))
  val wakeup_late_csr = Wire(Valid(new WakeupPort))

  val wakeups = Seq(wakeup_alu, wakeup_disp, wakeup_late_lsu, wakeup_late_csr)

  // Connect wakeup buses to BusyTable
  busyTable_.io.wakeup.zip(wakeups).foreach { case (bt_wk, wk) => bt_wk := wk }

  // ==========================================================
  // Stage pipeline: IFU -> Decode -> Rename -> Dispatch
  // ==========================================================
  PipelineConnect(io.in, decodeStage_.io.in, flush)
  PipelineConnect(decodeStage_.io.out, renameStage_.io.in, flush)
  PipelineConnect(renameStage_.io.out, dispatcher_.io.in, flush)

  // --- RenameStage side-band ---
  renameStage_.io.frat(0) <> futureRat_.io.read(0)
  renameStage_.io.frat(1) <> futureRat_.io.read(1)
  renameStage_.io.frat(2) <> futureRat_.io.read(2)
  futureRat_.io.write := renameStage_.io.frat_write

  busyTable_.io.set_busy := renameStage_.io.busy_set

  renameStage_.io.freelist_alloc <> freeList_.io.alloc

  // --- Dispatcher ---
  dispatcher_.io.flush := flush
  dispatcher_.io.rob_enq <> rob_.io.enq
  dispatcher_.io.rob_tag := rob_.io.enq_tag
  dispatcher_.io.alu_iq <> alu_iq_.io.enq
  dispatcher_.io.bru_iq <> bru_iq_.io.enq

  // ==========================================================
  // IQ → BusyTable read ports (readiness checked at issue time)
  // ==========================================================
  alu_iq_.io.busy_read(0) <> busyTable_.io.read(0)
  alu_iq_.io.busy_read(1) <> busyTable_.io.read(1)
  bru_iq_.io.busy_read(0) <> busyTable_.io.read(2)
  bru_iq_.io.busy_read(1) <> busyTable_.io.read(3)

  // ==========================================================
  // Stage 4 — Issue + Execute + Writeback
  // ==========================================================

  // --- ALU path ---
  alu_.io.out.ready := true.B
  alu_.io.in.valid := alu_iq_.io.issue.valid
  alu_iq_.io.issue.ready := alu_.io.in.ready
  val alu_issue = alu_iq_.io.issue.bits
  alu_.prs1 := alu_issue.prs1
  alu_.prs2 := alu_issue.prs2
  prf_.io.read(0) <> alu_.prf(0)
  prf_.io.read(1) <> alu_.prf(1)
  alu_.io.in.bits.alu_op := alu_issue.extra.alu_op
  alu_.io.in.bits.rob_tag := alu_issue.rob_tag
  alu_.io.in.bits.prd := alu_issue.extra.prd
  alu_.io.in.bits.prf_wen := alu_issue.extra.prf_wen
  alu_.io.in.bits.use_imm := alu_issue.extra.use_imm
  alu_.io.in.bits.imm := alu_issue.extra.imm

  val alu_wb_valid = alu_.io.out.fire
  val alu_wb_tag = alu_.io.out.bits.rob_tag
  val alu_wb_prd = alu_.io.out.bits.prd
  val alu_wb_prf_wen = alu_.io.out.bits.prf_wen
  val alu_result = alu_.io.out.bits.result

  rob_.io.alu.valid := alu_wb_valid
  rob_.io.alu.bits.tag := alu_wb_tag
  rob_.io.alu.bits.alu_result := alu_result

  // ALU PRF write (port 0): for R_ALU / I_ALU
  prf_.io.write(0).valid := alu_wb_valid && alu_wb_prf_wen && !flush
  prf_.io.write(0).bits.addr := alu_wb_prd
  prf_.io.write(0).bits.data := alu_result

  // ALU wakeup
  wakeup_alu.valid := alu_wb_valid && alu_wb_prf_wen && !flush
  wakeup_alu.bits.prd := alu_wb_prd

  // --- BRU path ---
  bru_.io.out.ready := true.B
  bru_iq_.io.issue.ready := bru_.io.in.ready
  bru_.io.in.valid := bru_iq_.io.issue.valid
  bru_.prs1 := bru_iq_.io.issue.bits.prs1
  bru_.prs2 := bru_iq_.io.issue.bits.prs2
  prf_.io.read(2) <> bru_.prf(0)
  prf_.io.read(3) <> bru_.prf(1)
  bru_.io.in.bits.op := bru_iq_.io.issue.bits.extra.bru_op
  bru_.io.in.bits.rob_tag := bru_iq_.io.issue.bits.rob_tag

  val bru_wb_valid = bru_.io.out.fire
  val bru_wb_tag = bru_.io.out.bits.rob_tag
  val br_flag = bru_.io.out.bits.br_flag

  rob_.io.bru.valid := bru_wb_valid
  rob_.io.bru.bits.tag := bru_wb_tag
  rob_.io.bru.bits.br_flag := br_flag

  // --- Late exec PRF read: LSU ports 4-5, CSRU port 6 ---
  prf_.io.read(4) <> lsu_.prf(0)
  prf_.io.read(5) <> lsu_.prf(1)
  prf_.io.read(6) <> csru_.prf(0)

  // ==========================================================
  // Dispatch-resolved PRF write (port 1): JAL/JALR/LUI/AUIPC
  // ==========================================================
  prf_.io.write(1) := dispatcher_.io.prf_write

  // ==========================================================
  // Stage 5 — Commit (CommitStage module)
  // ==========================================================

  // --- ROB commit ---
  commitStage_.rob <> rob_.io.commit

  // --- LSU late execution (driven by ROB) ---
  lsu_.late <> rob_.io.lsu

  // --- CSR late execution (driven by ROB) ---
  csru_.late <> rob_.io.csr

  // --- CSR exception (driven by CommitStage) ---
  csru_.except := commitStage_.csr.except
  commitStage_.csr.xepc := csru_.xepc
  commitStage_.csr.xtvec := csru_.xtvec

  // --- ArchRAT write ---
  archRat_.io.write := commitStage_.arch_rat_w

  // --- FreeList: free old_prd (also serves as commit_alloc) ---
  freeList_.io.free := commitStage_.freelist_free

  // --- LSU PRF write (port 2) ---
  prf_.io.write(2) := rob_.io.lsu_wb

  // --- CSR PRF write (port 3) ---
  prf_.io.write(3) := rob_.io.csr_wb

  // --- Late exec wakeup (LSU/CSR mutually exclusive, merge into one) ---
  wakeup_late_lsu.valid := rob_.io.lsu_wb.valid
  wakeup_late_lsu.bits.prd := rob_.io.lsu_wb.bits.addr
  wakeup_late_csr.valid := rob_.io.csr_wb.valid
  wakeup_late_csr.bits.prd := rob_.io.csr_wb.bits.addr

  // --- fence_i ---
  fence_i := commitStage_.fence_i

  // ==========================================================
  // Debug probe
  // ==========================================================
  // GPR: derived from ArchRAT (non-forwarded) + PRF
  prf_.probe.arch_rat := archRat_.io.snapshot
  probe.bits.pc := commitStage_.probe.bits.pc
  probe.bits.dnpc := commitStage_.probe.bits.dnpc
  probe.bits.inst := commitStage_.probe.bits.inst
  probe.bits.is_mmio := commitStage_.probe.bits.is_mmio
  probe.bits.gpr := prf_.probe.gpr
  probe.bits.csr := csru_.probe
  probe.bits.perf := commitStage_.perf
  probe.valid := commitStage_.probe.valid
}
