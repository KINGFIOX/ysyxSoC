package ysyx.core.backend

import chisel3._
import chisel3.util._
import chisel3.probe.{define, Probe, ProbeValue, read}

import freechips.rocketchip.amba.axi4._
import freechips.rocketchip.util._

import ysyx.core.common._
import ysyx.core.frontend._
import ysyx.core.lsu._
import ysyx.core.DebugBundle

class BackEnd extends NPCModule {

  val io = IO(new Bundle {
    val in = Flipped(Irrevocable(new IFUOutput))
    val redirect = Output(new RedirectBundle)
    val flush = Output(Bool())
  })

  // bus
  val dcache = IO(AXI4Bundle(axiParams))
  val perip = IO(AXI4Bundle(axiParams))
  val probe = IO(Output(Probe(new DebugBundle)))
  val interrupt = IO(Input(Bool()))
  val fence_i = IO(Output(Bool()))

  // ==========================================================
  // Sub-modules
  // ==========================================================
  val decodeStage_ = Module(new DecodeStage)
  val renameStage_ = Module(new RenameStage)
  val dispatcher_ = Module(new Dispatcher)

  val rfu_ = Module(new RFU)
  val alu_ = Module(new ALU)
  val bru_ = Module(new BRU)
  val agu_ = Module(new AGU)
  val rob_ = Module(new Rob)
  val rat_ = Module(new RAT)
  val alu_iq_ = Module(new ALUIssueQueue)
  val bru_iq_ = Module(new BRUIssueQueue)
  val agu_iq_ = Module(new AGUIssueQueue)
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
  val flush = WireDefault(false.B)
  val redirect = Wire(new RedirectBundle)
  redirect.valid := false.B
  redirect.target := 0.U

  io.flush := flush
  io.redirect := redirect

  rob_.io.flush := flush
  rat_.io.flush := flush
  alu_iq_.io.flush := flush
  bru_iq_.io.flush := flush
  agu_iq_.io.flush := flush

  // ==========================================================
  // CDB wires (declared early for rename / dispatch bypass)
  // ==========================================================
  val cdb1 = Wire(new CDBBundle)
  val cdb2 = Wire(new CDBBundle)

  alu_iq_.io.cdb1 := cdb1
  alu_iq_.io.cdb2 := cdb2
  bru_iq_.io.cdb1 := cdb1
  bru_iq_.io.cdb2 := cdb2
  agu_iq_.io.cdb1 := cdb1
  agu_iq_.io.cdb2 := cdb2

  // ==========================================================
  // Stage pipeline: IFU -> Decode -> Rename -> Dispatch
  // ==========================================================
  decodeStage_.io.in <> io.in
  renameStage_.io.in <> decodeStage_.io.out
  dispatcher_.io.in <> renameStage_.io.out

  // --- RenameStage side-band ---
  renameStage_.io.rat.rs1 <> rat_.io.read1
  renameStage_.io.rat.rs2 <> rat_.io.read2

  rfu_.io.in.rs1_i := renameStage_.io.rfu.rs1_i
  rfu_.io.in.rs2_i := renameStage_.io.rfu.rs2_i
  renameStage_.io.rfu.rs1_v := rfu_.io.out.rs1_v
  renameStage_.io.rfu.rs2_v := rfu_.io.out.rs2_v

  renameStage_.io.rob.fwd1 <> rob_.io.fwd1
  renameStage_.io.rob.fwd2 <> rob_.io.fwd2

  renameStage_.io.disp_fwd <> dispatcher_.io.rename_fwd

  renameStage_.io.cdb1 := cdb1
  renameStage_.io.cdb2 := cdb2

  // --- Dispatcher ---
  dispatcher_.io.flush := flush
  dispatcher_.io.rob_enq <> rob_.io.enq
  dispatcher_.io.rob_tag := rob_.io.enq_tag
  dispatcher_.io.disp_alu <> alu_iq_.io.enq
  dispatcher_.io.disp_bru <> bru_iq_.io.enq
  dispatcher_.io.disp_agu <> agu_iq_.io.enq

  rat_.io.write <> dispatcher_.io.rat_write

  // ==========================================================
  // Stage 4 — Issue + Execute + Writeback
  // ==========================================================

  // --- ALU path ---
  alu_.io.in.valid := alu_iq_.io.issue.valid
  alu_.io.in.bits.op1 := alu_iq_.io.issue.bits.src1_v
  alu_.io.in.bits.op2 := alu_iq_.io.issue.bits.src2_v
  alu_.io.in.bits.aluOp := alu_iq_.io.issue.bits.extra.aluOp
  alu_iq_.io.issue.ready := alu_.io.in.ready

  alu_.io.out.ready := true.B

  val alu_issue_valid = alu_iq_.io.issue.fire
  val alu_issue_tag = alu_iq_.io.issue.bits.rob_tag
  val alu_issue_rd_def = alu_iq_.io.issue.bits.extra.rd_def
  val alu_result = alu_.io.out.bits.result

  rob_.io.lookup1.tag := alu_issue_tag
  val alu_rob_entry = rob_.io.lookup1.entry

  val alu_rd_val = Mux(alu_rob_entry.rd_val_valid, alu_rob_entry.rd_val, alu_result)
  val alu_rd_val_valid = true.B // TODO:

  val alu_is_jalr = alu_rob_entry.is_jalr
  val alu_mispredict = alu_is_jalr
  val alu_target_npc = Mux(alu_is_jalr, alu_result & ~1.U(addrBits.W), alu_rob_entry.pc + 4.U)

  rob_.io.alu.valid := alu_issue_valid
  rob_.io.alu.bits.tag := alu_issue_tag
  rob_.io.alu.bits.alu_result := alu_result
  rob_.io.alu.bits.rd_val := alu_rd_val
  rob_.io.alu.bits.rd_val_valid := alu_rd_val_valid
  rob_.io.alu.bits.mispredict := alu_mispredict
  rob_.io.alu.bits.target_npc := alu_target_npc

  // --- BRU path ---
  bru_.io.in.valid := bru_iq_.io.issue.valid
  bru_.io.in.bits.rs1_v := bru_iq_.io.issue.bits.src1_v
  bru_.io.in.bits.rs2_v := bru_iq_.io.issue.bits.src2_v
  bru_.io.in.bits.op := bru_iq_.io.issue.bits.extra.bru_op
  bru_iq_.io.issue.ready := bru_.io.in.ready

  bru_.io.out.ready := true.B

  val bru_issue_valid = bru_iq_.io.issue.fire
  val bru_issue_tag = bru_iq_.io.issue.bits.rob_tag
  val br_flag = bru_.io.out.bits.br_flag

  rob_.io.lookup2.tag := bru_issue_tag
  val bru_rob_entry = rob_.io.lookup2.entry

  val bru_mispredict = br_flag
  val bru_actual_npc = Mux(br_flag, bru_rob_entry.target_npc, bru_rob_entry.pc + 4.U)

  rob_.io.bru.valid := bru_issue_valid
  rob_.io.bru.bits.tag := bru_issue_tag
  rob_.io.bru.bits.mispredict := bru_mispredict
  rob_.io.bru.bits.actual_npc := bru_actual_npc

  // --- AGU path ---
  agu_.io.in.valid := agu_iq_.io.issue.valid
  agu_.io.in.bits.base := agu_iq_.io.issue.bits.src1_v
  agu_.io.in.bits.offset := agu_iq_.io.issue.bits.extra.imm
  agu_iq_.io.issue.ready := agu_.io.in.ready

  agu_.io.out.ready := true.B

  rob_.io.agu.valid := agu_iq_.io.issue.fire
  rob_.io.agu.bits.tag := agu_iq_.io.issue.bits.rob_tag
  rob_.io.agu.bits.addr := agu_.io.out.bits.addr
  rob_.io.agu.bits.wdata := agu_iq_.io.issue.bits.src2_v
  rob_.io.agu.bits.is_mmio := agu_.io.out.bits.is_mmio

  // --- CDB1 — ALU writeback broadcast ---
  cdb1.valid := alu_issue_valid && alu_rd_val_valid && alu_issue_rd_def && !flush
  cdb1.tag := alu_issue_tag
  cdb1.value := alu_rd_val

  // ==========================================================
  // Stage 5 — Commit (CommitStage module)
  // ==========================================================
  val commitStage_ = Module(new CommitStage)

  // --- ROB commit ---
  commitStage_.io.rob.commit <> rob_.io.commit
  rob_.io.wb_commit := commitStage_.io.rob.wb_commit

  // --- CSR exception ---
  csru_.io.commit := commitStage_.io.csr.exception

  // --- CSR retire (late execute) ---
  csru_.io.late <> commitStage_.io.csr.retire.late
  csru_.io.addr := commitStage_.io.csr.retire.addr
  csru_.io.wop := commitStage_.io.csr.retire.wop
  csru_.io.wen := commitStage_.io.csr.retire.wen
  csru_.io.wdata := commitStage_.io.csr.retire.wdata
  commitStage_.io.csr.xepc := csru_.io.xepc
  commitStage_.io.csr.xtvec := csru_.io.xtvec

  // --- LSU commit ---
  lsu_.io.late <> commitStage_.io.lsu.late
  lsu_.io.addr := commitStage_.io.lsu.addr
  lsu_.io.size := commitStage_.io.lsu.size
  lsu_.io.sign_ext := commitStage_.io.lsu.sign_ext
  lsu_.io.r_en := commitStage_.io.lsu.r_en
  lsu_.io.w_en := commitStage_.io.lsu.w_en
  lsu_.io.wdata := commitStage_.io.lsu.wdata
  lsu_.io.is_mmio := commitStage_.io.lsu.is_mmio

  // --- RFU writeback ---
  rfu_.io.in.wen := commitStage_.io.rfu.wen
  rfu_.io.in.rd_i := commitStage_.io.rfu.rd_i
  rfu_.io.in.wdata := commitStage_.io.rfu.wdata

  // --- RAT commit ---
  rat_.io.commit := commitStage_.io.rat_commit

  // --- IFU flush + redirect ---
  flush := commitStage_.io.ifu.flush
  redirect := commitStage_.io.ifu.redirect

  // --- CDB2 ---
  cdb2 := commitStage_.io.cdb2

  // --- fence_i ---
  fence_i := commitStage_.io.fence_i

  // ==========================================================
  // Debug probe
  // ==========================================================
  val dbg = Wire(new DebugBundle)
  dbg.valid := commitStage_.io.debug.valid
  dbg.pc := commitStage_.io.debug.pc
  dbg.dnpc := commitStage_.io.debug.dnpc
  dbg.inst := commitStage_.io.debug.inst
  dbg.isMMIO := commitStage_.io.debug.is_mmio
  dbg.gpr := VecInit((0 until NRReg).map(i => read(rfu_.io.probe)(i)))
  dbg.csr := read(csru_.io.probe)
  define(probe, ProbeValue(dbg))
}
