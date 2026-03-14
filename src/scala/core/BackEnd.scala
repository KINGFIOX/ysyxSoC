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
  // AXI — dcache pass-through to LSU
  // ==========================================================
  lsu_.dcache <> dcache

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
  val alu_target_npc =
    Mux(alu_is_jalr, alu_result & ~1.U(addrBits.W), alu_rob_entry.pc + 4.U)

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
  val bru_actual_npc =
    Mux(br_flag, bru_rob_entry.target_npc, bru_rob_entry.pc + 4.U)

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

  // --- CDB1 — ALU writeback broadcast ---
  cdb1.valid := alu_issue_valid && alu_rd_val_valid && alu_issue_rd_def && !flush
  cdb1.tag := alu_issue_tag
  cdb1.value := alu_rd_val

  // ==========================================================
  // Stage 5 — Commit + Late Execute
  // ==========================================================
  object CommitState extends ChiselEnum {
    val idle, late_wait = Value
  }
  val commitStateQ = RegInit(CommitState.idle)

  val head = rob_.io.commit.bits.entry
  val head_tag = rob_.io.commit.bits.tag
  val head_valid = rob_.io.commit.valid

  val head_is_mem = head.mem.r_en || head.mem.w_en
  val head_is_csr = head.csr_wen

  // CDB2 defaults
  cdb2.valid := false.B
  cdb2.tag := head_tag
  cdb2.value := 0.U

  // Commit-time writeback to ROB defaults
  rob_.io.wb_commit.valid := false.B
  rob_.io.wb_commit.bits.tag := head_tag
  rob_.io.wb_commit.bits.value := 0.U

  rob_.io.commit.ready := false.B

  // RFU write defaults
  rfu_.io.in.wen := false.B
  rfu_.io.in.rd_i := head.rd_idx
  rfu_.io.in.wdata := head.rd_val

  // RAT commit-clear defaults
  rat_.io.commit.en := false.B
  rat_.io.commit.addr := head.rd_idx
  rat_.io.commit.tag := head_tag

  // CSRU defaults
  csru_.io.late.req := false.B
  csru_.io.addr := head.imm(NRCSRbits - 1, 0)
  csru_.io.wop := head.csr_op
  csru_.io.wen := false.B
  csru_.io.wdata := head.alu_result
  csru_.io.commit.xepc := head.pc
  csru_.io.commit.xepc_wen := false.B
  csru_.io.commit.xcause := head.mcause
  csru_.io.commit.xcause_wen := false.B
  csru_.io.commit.xtval := head.xtval
  csru_.io.commit.xtval_wen := false.B

  // LSU defaults
  lsu_.io.late.req := false.B
  lsu_.io.addr := head.mem.addr
  lsu_.io.size := head.mem.size
  lsu_.io.sign_ext := head.mem.sign_ext
  lsu_.io.r_en := head.mem.r_en
  lsu_.io.w_en := head.mem.w_en
  lsu_.io.wdata := head.mem.wdata

  fence_i := false.B

  // Debug signals
  val commit_valid_dbg = RegInit(false.B)
  val commit_pc_dbg = Reg(UInt(dataBits.W))
  val commit_dnpc_dbg = Reg(UInt(dataBits.W))
  val commit_inst_dbg = Reg(UInt(instBits.W))
  commit_valid_dbg := false.B

  // ---- Commit state machine ----
  switch(commitStateQ) {
    is(CommitState.idle) {
      when(head_valid) {
        when(head.except_en) {
          csru_.io.commit.xepc_wen := true.B
          csru_.io.commit.xcause_wen := true.B
          csru_.io.commit.xtval_wen := true.B

          flush := true.B
          redirect.valid := true.B
          redirect.target := csru_.io.xtvec

          rob_.io.commit.ready := true.B

          commit_valid_dbg := true.B
          commit_pc_dbg := head.pc
          commit_dnpc_dbg := csru_.io.xtvec
          commit_inst_dbg := head.inst

        }.elsewhen(head_is_mem) {
          lsu_.io.late.req := true.B
          when(lsu_.io.late.done) {
            when(head.rd_def) {
              rfu_.io.in.wen := true.B
              rfu_.io.in.wdata := lsu_.io.late.result
            }
            rob_.io.wb_commit.valid := head.rd_def
            rob_.io.wb_commit.bits.value := lsu_.io.late.result
            cdb2.valid := head.rd_def
            cdb2.value := lsu_.io.late.result

            rat_.io.commit.en := head.rd_def
            rob_.io.commit.ready := true.B

            commit_valid_dbg := true.B
            commit_pc_dbg := head.pc
            commit_dnpc_dbg := head.pc + 4.U
            commit_inst_dbg := head.inst
          }.otherwise {
            commitStateQ := CommitState.late_wait
          }

        }.elsewhen(head_is_csr) {
          csru_.io.late.req := true.B
          csru_.io.wen := head.csr_wen
          val csr_rd = csru_.io.late.result

          when(head.rd_def) {
            rfu_.io.in.wen := true.B
            rfu_.io.in.wdata := csr_rd
          }

          rob_.io.wb_commit.valid := head.rd_def
          rob_.io.wb_commit.bits.value := csr_rd
          cdb2.valid := head.rd_def
          cdb2.value := csr_rd

          rat_.io.commit.en := head.rd_def

          rob_.io.commit.ready := true.B

          commit_valid_dbg := true.B
          commit_pc_dbg := head.pc
          commit_dnpc_dbg := head.pc + 4.U
          commit_inst_dbg := head.inst

        }.elsewhen(head.is_mret) {
          flush := true.B
          redirect.valid := true.B
          redirect.target := csru_.io.xepc

          rob_.io.commit.ready := true.B

          commit_valid_dbg := true.B
          commit_pc_dbg := head.pc
          commit_dnpc_dbg := csru_.io.xepc
          commit_inst_dbg := head.inst

        }.otherwise {
          when(head.rd_def) {
            rfu_.io.in.wen := true.B
            rfu_.io.in.wdata := head.rd_val
          }

          rat_.io.commit.en := head.rd_def

          when(head.mispredict) {
            flush := true.B
            redirect.valid := true.B
            redirect.target := head.target_npc
          }

          rob_.io.commit.ready := true.B

          commit_valid_dbg := true.B
          commit_pc_dbg := head.pc
          commit_dnpc_dbg := Mux(
            head.mispredict,
            head.target_npc,
            head.pc + 4.U
          )
          commit_inst_dbg := head.inst
        }
      }
    }

    is(CommitState.late_wait) {
      lsu_.io.late.req := true.B
      when(lsu_.io.late.done) {
        when(head.rd_def) {
          rfu_.io.in.wen := true.B
          rfu_.io.in.wdata := lsu_.io.late.result
        }

        rob_.io.wb_commit.valid := head.rd_def
        rob_.io.wb_commit.bits.value := lsu_.io.late.result
        cdb2.valid := head.rd_def
        cdb2.value := lsu_.io.late.result

        rat_.io.commit.en := head.rd_def
        rob_.io.commit.ready := true.B
        commitStateQ := CommitState.idle

        commit_valid_dbg := true.B
        commit_pc_dbg := head.pc
        commit_dnpc_dbg := head.pc + 4.U
        commit_inst_dbg := head.inst
      }
    }
  }

  // ==========================================================
  // Debug probe
  // ==========================================================
  val dbg = Wire(new DebugBundle)
  dbg.valid := commit_valid_dbg
  dbg.pc := commit_pc_dbg
  dbg.dnpc := commit_dnpc_dbg
  dbg.inst := commit_inst_dbg
  dbg.isMMIO := false.B
  dbg.gpr := VecInit((0 until NRReg).map(i => read(rfu_.io.probe)(i)))
  dbg.csr := read(csru_.io.probe)
  define(probe, ProbeValue(dbg))
}
