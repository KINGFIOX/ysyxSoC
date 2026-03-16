package ysyx.core.backend

import chisel3._
import chisel3.util._

import freechips.rocketchip.amba.axi4._
import freechips.rocketchip.util._

import ysyx.core.common._
import ysyx.core.frontend._
import ysyx.core.lsu._
import ysyx.core.DebugBundle

class BackEnd extends NPCModule {

  // connect to frontend
  val io = IO(new Bundle {
    val in = Flipped(Decoupled(new IFUOutput))
    val redirect = Output(new RedirectBundle)
    val flush = Output(Bool())
  })

  // connect to bus
  val dcache = IO(AXI4Bundle(axiParams))
  val perip = IO(AXI4Bundle(axiParams))
  val interrupt = IO(Input(Bool()))
  val fence_i = IO(Output(Bool()))
  val probe = IO(Output(new DebugBundle))

  // ==========================================================
  // Sub-modules
  // ==========================================================
  val decodeStage_ = Module(new DecodeStage)
  val renameStage_ = Module(new RenameStage)
  val dispatcher_ = Module(new Dispatcher)

  val rfu_ = Module(new RFU)
  val rob_ = Module(new Rob)
  val rat_ = Module(new RAT)
  val alu_ = Module(new ALU)
  val bru_ = Module(new BRU)
  val agu_ = Module(new AGU)
  val alu_iq_ = Module(new ALUIssueQueue)
  val bru_iq_ = Module(new BRUIssueQueue)
  val agu_iq_ = Module(new AGUIssueQueue)
  val lsu_ = Module(new LSU)
  val csru_ = Module(new CSRU)
  val commitStage_ = Module(new CommitStage)

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
  io.redirect := commitStage_.ifu

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
  PipelineConnect(io.in, decodeStage_.io.in, flush)
  PipelineConnect(decodeStage_.io.out, renameStage_.io.in, flush)
  PipelineConnect(renameStage_.io.out, dispatcher_.io.in, flush)

  // --- RenameStage side-band ---
  renameStage_.io.rat_query(0) <> rat_.io.read(0)
  renameStage_.io.rat_query(1) <> rat_.io.read(1)

  rfu_.io.read(0).addr := renameStage_.io.rfu_query(0).addr
  rfu_.io.read(1).addr := renameStage_.io.rfu_query(1).addr
  renameStage_.io.rfu_query(0).data := rfu_.io.read(0).data
  renameStage_.io.rfu_query(1).data := rfu_.io.read(1).data

  renameStage_.io.rob_query(0) <> rob_.io.forward(0)
  renameStage_.io.rob_query(1) <> rob_.io.forward(1)

  renameStage_.io.disp_fwd <> dispatcher_.io.rename_fwd

  renameStage_.io.cdb(0) := cdb1
  renameStage_.io.cdb(1) := cdb2

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
  alu_.io.in.bits.op1 := alu_iq_.io.issue.bits.src_v(0)
  alu_.io.in.bits.op2 := alu_iq_.io.issue.bits.src_v(1)
  alu_.io.in.bits.alu_op := alu_iq_.io.issue.bits.extra.alu_op
  alu_.io.in.bits.rob_tag := alu_iq_.io.issue.bits.rob_tag
  alu_.io.in.bits.rd_def := alu_iq_.io.issue.bits.extra.rd_def
  alu_iq_.io.issue.ready := alu_.io.in.ready

  alu_.io.out.ready := true.B

  val alu_wb_valid = alu_.io.out.fire
  val alu_wb_tag = alu_.io.out.bits.rob_tag
  val alu_wb_rd_def = alu_.io.out.bits.rd_def
  val alu_result = alu_.io.out.bits.result

  rob_.io.lookup(0).tag := alu_wb_tag
  val alu_rob_entry = rob_.io.lookup(0).entry

  val alu_is_jalr = alu_rob_entry.inst_type === InstType.JALR
  val alu_is_csr = alu_rob_entry.inst_type === InstType.CSR

  val alu_rd_val = Mux(alu_rob_entry.rd.valid, alu_rob_entry.rd.value, alu_result)
  val alu_rd_valid = !alu_is_csr
  val alu_target_npc = Mux(alu_is_jalr, alu_result & ~1.U(addrBits.W), alu_rob_entry.target_npc)

  rob_.io.alu.valid := alu_wb_valid
  rob_.io.alu.bits.tag := alu_wb_tag
  rob_.io.alu.bits.rd_value := alu_rd_val
  rob_.io.alu.bits.rd_valid := alu_rd_valid
  rob_.io.alu.bits.target_npc := alu_target_npc
  rob_.io.alu.bits.csr_wdata := alu_result

  // --- BRU path ---
  bru_.io.in.valid := bru_iq_.io.issue.valid
  bru_.io.in.bits.rs1_v := bru_iq_.io.issue.bits.src_v(0)
  bru_.io.in.bits.rs2_v := bru_iq_.io.issue.bits.src_v(1)
  bru_.io.in.bits.op := bru_iq_.io.issue.bits.extra.bru_op
  bru_.io.in.bits.rob_tag := bru_iq_.io.issue.bits.rob_tag
  bru_iq_.io.issue.ready := bru_.io.in.ready

  bru_.io.out.ready := true.B

  val bru_wb_valid = bru_.io.out.fire
  val bru_wb_tag = bru_.io.out.bits.rob_tag
  val br_flag = bru_.io.out.bits.br_flag

  rob_.io.lookup(1).tag := bru_wb_tag
  val bru_rob_entry = rob_.io.lookup(1).entry

  val bru_target_npc = Mux(br_flag, bru_rob_entry.target_npc, bru_rob_entry.pc + 4.U)

  rob_.io.bru.valid := bru_wb_valid
  rob_.io.bru.bits.tag := bru_wb_tag
  rob_.io.bru.bits.target_npc := bru_target_npc

  // --- AGU path ---
  agu_.io.in.valid := agu_iq_.io.issue.valid
  agu_.io.in.bits.base := agu_iq_.io.issue.bits.src_v(0)
  agu_.io.in.bits.offset := agu_iq_.io.issue.bits.extra.imm
  agu_.io.in.bits.rob_tag := agu_iq_.io.issue.bits.rob_tag
  agu_.io.in.bits.wdata := agu_iq_.io.issue.bits.src_v(1)
  agu_iq_.io.issue.ready := agu_.io.in.ready

  agu_.io.out.ready := true.B

  rob_.io.agu.valid := agu_.io.out.fire
  rob_.io.agu.bits.tag := agu_.io.out.bits.rob_tag
  rob_.io.agu.bits.addr := agu_.io.out.bits.addr
  rob_.io.agu.bits.wdata := agu_.io.out.bits.wdata
  rob_.io.agu.bits.is_mmio := agu_.io.out.bits.is_mmio

  // --- CDB1 — ALU writeback broadcast ---
  cdb1.valid := alu_wb_valid && alu_rd_valid && alu_wb_rd_def && !flush
  cdb1.tag := alu_wb_tag
  cdb1.value := alu_rd_val

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

  // --- RFU writeback ---
  rfu_.io.write <> commitStage_.rfu_w
  rat_.io.commit <> commitStage_.rat

  // --- CDB2 ---
  cdb2 := commitStage_.cdb

  // --- fence_i ---
  fence_i := commitStage_.fence_i

  // ==========================================================
  // Debug probe
  // ==========================================================
  val dbg = Wire(new DebugBundle)
  dbg.valid := commitStage_.probe.valid
  dbg.pc := commitStage_.probe.pc
  dbg.dnpc := commitStage_.probe.dnpc
  dbg.inst := commitStage_.probe.inst
  dbg.is_mmio := commitStage_.probe.is_mmio
  dbg.gpr := rfu_.probe
  dbg.csr := csru_.probe
  probe := dbg
}
