package ysyx.core

import chisel3._
import chisel3.util._
import chisel3.probe.{define, Probe, ProbeValue, read}

import ysyx.core.common.{HasCSRParameter, HasCoreParameter, HasRegFileParameter}
import ysyx.core.component._
import freechips.rocketchip.amba.axi4._
import freechips.rocketchip.util._
import ysyx.core.common.NPCModule

class BackEnd extends NPCModule {

  val dcache = IO(AXI4Bundle(axiParams))

  val io = IO(new Bundle {
    val in = Flipped(Irrevocable(new IFUOutput))
    val redirect = Output(new RedirectBundle)
    val flush = Output(Bool())
  })

  val probe = IO(Output(Probe(new DebugBundle)))
  val interrupt = IO(Input(Bool()))
  val fence_i = IO(Output(Bool()))

  // ==========================================================
  // Sub-modules
  // ==========================================================
  val cu_     = Module(new CU)
  val igu_    = Module(new IGU)
  val rfu_    = Module(new RFU)
  val alu_    = Module(new ALU)
  val bru_    = Module(new BRU)
  val agu_    = Module(new AGU)
  val rob_    = Module(new Rob)
  val rat_    = Module(new RAT)
  val alu_iq_ = Module(new ALUIssueQueue)
  val bru_iq_ = Module(new BRUIssueQueue)
  val agu_iq_ = Module(new AGUIssueQueue)
  val lsu_    = Module(new LSU)
  val csru_   = Module(new CSRU)

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

  rob_.io.flush    := flush
  rat_.io.flush    := flush
  alu_iq_.io.flush := flush
  bru_iq_.io.flush := flush
  agu_iq_.io.flush := flush

  // ==========================================================
  // CDB wires (declared early for rename / dispatch bypass)
  // ==========================================================
  val cdb1 = Wire(new CDBBundle)
  val cdb2 = Wire(new CDBBundle)

  alu_iq_.io.cdb1 := cdb1;  alu_iq_.io.cdb2 := cdb2
  bru_iq_.io.cdb1 := cdb1;  bru_iq_.io.cdb2 := cdb2
  agu_iq_.io.cdb1 := cdb1;  agu_iq_.io.cdb2 := cdb2

  // ==========================================================
  // Stage 1 — Decode
  // ==========================================================
  val ifu_out   = io.in.bits
  val ifu_valid = io.in.valid

  cu_.io.in.inst := ifu_out.inst
  igu_.io.in.inst_31_7 := ifu_out.inst(31, 7)
  igu_.io.in.immType   := cu_.io.out.immType

  val rs1_idx     = ifu_out.inst(19, 15)
  val rs2_idx     = ifu_out.inst(24, 20)
  val rd_idx      = ifu_out.inst(11, 7)
  val imm         = igu_.io.out.imm
  val dispatch_pc = ifu_out.pc

  val npcOp   = cu_.io.out.npcOp
  val aluSel1 = cu_.io.out.aluSel1
  val aluSel2 = cu_.io.out.aluSel2

  val is_jal    = npcOp === NPCOpType.NPC_JAL
  val is_mret   = npcOp === NPCOpType.NPC_MRET
  val is_lui    = aluSel1 === ALUOp1Sel.OP1_ZERO
  val is_auipc  = aluSel1 === ALUOp1Sel.OP1_PC && npcOp === NPCOpType.NPC_4
  val is_branch = npcOp === NPCOpType.NPC_BR

  val ifu_except_en      = ifu_out.exceptionEn
  val cu_except_en       = cu_.io.out.exceptionEn
  val dispatch_except_en = ifu_except_en || cu_except_en

  val is_mem = cu_.io.out.mem.r_en || cu_.io.out.mem.w_en

  val dispatch_resolved = is_jal || is_mret || is_lui || is_auipc
  val skip_iq           = dispatch_except_en || dispatch_resolved
  val go_to_alu         = !skip_iq && !is_branch && !is_mem
  val go_to_bru         = is_branch && !skip_iq
  val go_to_agu         = is_mem && !skip_iq
  val rd_def            = cu_.io.out.rfWen && (rd_idx =/= 0.U)

  // ==========================================================
  // Stage 2 — Rename (RAT read + RFU read + ROB fwd + CDB bypass)
  // ==========================================================
  rat_.io.read1.addr := rs1_idx
  rat_.io.read2.addr := rs2_idx

  rfu_.io.in.rs1_i := rs1_idx
  rfu_.io.in.rs2_i := rs2_idx

  val needs_rs1 = (aluSel1 === ALUOp1Sel.OP1_RS1) || is_branch
  val needs_rs2 = (aluSel2 === ALUOp2Sel.OP2_RS2) || is_branch || cu_.io.out.mem.w_en

  // --- Source 1 ---
  val rename_src1 = Wire(new IQSrcBundle)
  rob_.io.fwd1.tag := rat_.io.read1.tag

  when(!needs_rs1) {
    rename_src1.ready := true.B
    rename_src1.value := 0.U
    rename_src1.tag   := 0.U
  }.elsewhen(!rat_.io.read1.busy) {
    rename_src1.ready := true.B
    rename_src1.value := rfu_.io.out.rs1_v
    rename_src1.tag   := 0.U
  }.otherwise {
    val fwd_tag = rat_.io.read1.tag
    val fwd_rdy = rob_.io.fwd1.valid ||
      (cdb1.valid && cdb1.tag === fwd_tag) ||
      (cdb2.valid && cdb2.tag === fwd_tag)
    val fwd_val = MuxCase(
      rob_.io.fwd1.value,
      Seq(
        (cdb1.valid && cdb1.tag === fwd_tag) -> cdb1.value,
        (cdb2.valid && cdb2.tag === fwd_tag) -> cdb2.value
      )
    )
    rename_src1.ready := fwd_rdy
    rename_src1.value := fwd_val
    rename_src1.tag   := fwd_tag
  }

  // --- Source 2 ---
  val rename_src2 = Wire(new IQSrcBundle)
  rob_.io.fwd2.tag := rat_.io.read2.tag

  when(!needs_rs2) {
    rename_src2.ready := true.B
    rename_src2.value := 0.U
    rename_src2.tag   := 0.U
  }.elsewhen(!rat_.io.read2.busy) {
    rename_src2.ready := true.B
    rename_src2.value := rfu_.io.out.rs2_v
    rename_src2.tag   := 0.U
  }.otherwise {
    val fwd_tag = rat_.io.read2.tag
    val fwd_rdy = rob_.io.fwd2.valid ||
      (cdb1.valid && cdb1.tag === fwd_tag) ||
      (cdb2.valid && cdb2.tag === fwd_tag)
    val fwd_val = MuxCase(
      rob_.io.fwd2.value,
      Seq(
        (cdb1.valid && cdb1.tag === fwd_tag) -> cdb1.value,
        (cdb2.valid && cdb2.tag === fwd_tag) -> cdb2.value
      )
    )
    rename_src2.ready := fwd_rdy
    rename_src2.value := fwd_val
    rename_src2.tag   := fwd_tag
  }

  // Dispatch-resolved value computation
  val disp_rd_val = MuxCase(0.U, Seq(
    is_jal   -> (dispatch_pc + 4.U),
    is_lui   -> imm,
    is_auipc -> (dispatch_pc + imm)
  ))
  val disp_rd_val_valid = is_jal || is_lui || is_auipc
  val disp_mispredict   = is_jal || is_mret
  val disp_target_npc = MuxCase(0.U, Seq(
    is_jal    -> (dispatch_pc + imm),
    is_branch -> (dispatch_pc + imm)
  ))

  // Exception mcause mapping
  val dispatch_mcause = MuxCase(0.U, Seq(
    ifu_except_en -> MuxLookup(ifu_out.exception, 0.U)(Seq(
      IFUExceptionType.ifu_INSTRUCTION_ADDRESS_MISALIGNED -> 0.U,
      IFUExceptionType.ifu_INSTRUCTION_ACCESS_FAULT       -> 1.U,
      IFUExceptionType.ifu_INSTRUCTION_PAGE_FAULT         -> 12.U
    )),
    cu_except_en -> MuxLookup(cu_.io.out.exception, 0.U)(Seq(
      CUExceptionType.cu_ILLEGAL_INSTRUCTION -> 2.U,
      CUExceptionType.cu_BREAKPOINT          -> 3.U,
      CUExceptionType.cu_ECALL_FROM_U_MODE   -> 8.U,
      CUExceptionType.cu_ECALL_FROM_S_MODE   -> 9.U,
      CUExceptionType.cu_ECALL_FROM_M_MODE   -> 11.U
    ))
  ))
  val dispatch_mtval = Mux(ifu_except_en, ifu_out.mtval, cu_.io.out.mtval)

  // ==========================================================
  // Stage 3 — Dispatch (ROB enqueue + IQ enqueue + RAT write)
  // ==========================================================
  val iq_ready = MuxCase(true.B, Seq(
    go_to_alu -> alu_iq_.io.enq.ready,
    go_to_bru -> bru_iq_.io.enq.ready,
    go_to_agu -> agu_iq_.io.enq.ready
  ))
  val can_dispatch = ifu_valid && rob_.io.enq.ready && iq_ready && !flush
  io.in.ready := rob_.io.enq.ready && iq_ready && !flush

  // --- ROB enqueue ---
  rob_.io.enq.valid := can_dispatch

  val rob_enq = rob_.io.enq.bits
  rob_enq.wbSel             := cu_.io.out.wbSel
  rob_enq.mem               := cu_.io.out.mem
  rob_enq.csrOp             := cu_.io.out.csrOp
  rob_enq.csrWen            := cu_.io.out.csrWen
  rob_enq.npcOp             := npcOp
  rob_enq.pc                := dispatch_pc
  rob_enq.inst              := ifu_out.inst
  rob_enq.imm               := imm
  rob_enq.rd_idx            := rd_idx
  rob_enq.rd_def            := rd_def
  rob_enq.except_en         := dispatch_except_en
  rob_enq.mcause            := dispatch_mcause
  rob_enq.mtval             := dispatch_mtval
  rob_enq.dispatch_executed := dispatch_resolved
  rob_enq.rd_val            := disp_rd_val
  rob_enq.rd_val_valid      := disp_rd_val_valid
  rob_enq.mispredict        := disp_mispredict
  rob_enq.target_npc        := disp_target_npc

  val rob_tag = rob_.io.enq_tag

  // --- ALU IQ enqueue ---
  alu_iq_.io.enq.valid     := can_dispatch && go_to_alu
  alu_iq_.io.enq.bits.src1 := rename_src1

  val imm_as_src2 = aluSel2 === ALUOp2Sel.OP2_IMM
  alu_iq_.io.enq.bits.src2.value := Mux(imm_as_src2, imm, rename_src2.value)
  alu_iq_.io.enq.bits.src2.tag   := Mux(imm_as_src2, 0.U, rename_src2.tag)
  alu_iq_.io.enq.bits.src2.ready := Mux(imm_as_src2, true.B, rename_src2.ready)

  alu_iq_.io.enq.bits.extra.aluOp  := cu_.io.out.aluOp
  alu_iq_.io.enq.bits.extra.rd_def := rd_def
  alu_iq_.io.enq.bits.rob_tag      := rob_tag

  // --- BRU IQ enqueue ---
  bru_iq_.io.enq.valid         := can_dispatch && go_to_bru
  bru_iq_.io.enq.bits.src1     := rename_src1
  bru_iq_.io.enq.bits.src2     := rename_src2
  bru_iq_.io.enq.bits.extra.bruOp := cu_.io.out.bruOp
  bru_iq_.io.enq.bits.rob_tag  := rob_tag

  // --- AGU IQ enqueue ---
  agu_iq_.io.enq.valid     := can_dispatch && go_to_agu
  agu_iq_.io.enq.bits.src1 := rename_src1

  val is_load = cu_.io.out.mem.r_en
  agu_iq_.io.enq.bits.src2.value := Mux(is_load, 0.U, rename_src2.value)
  agu_iq_.io.enq.bits.src2.tag   := Mux(is_load, 0.U, rename_src2.tag)
  agu_iq_.io.enq.bits.src2.ready := Mux(is_load, true.B, rename_src2.ready)

  agu_iq_.io.enq.bits.extra.imm := imm
  agu_iq_.io.enq.bits.rob_tag   := rob_tag

  // --- RAT update ---
  val should_rename = can_dispatch && rd_def && !dispatch_except_en
  rat_.io.write.en   := should_rename
  rat_.io.write.addr := rd_idx
  rat_.io.write.tag  := rob_tag

  // ==========================================================
  // Stage 4 — Issue + Execute + Writeback
  // ==========================================================

  // --- ALU path ---
  alu_iq_.io.issue.ready := true.B

  alu_.io.in.op1   := alu_iq_.io.issue.bits.src1_v
  alu_.io.in.op2   := alu_iq_.io.issue.bits.src2_v
  alu_.io.in.aluOp := alu_iq_.io.issue.bits.extra.aluOp

  val alu_issue_valid  = alu_iq_.io.issue.fire
  val alu_issue_tag    = alu_iq_.io.issue.bits.rob_tag
  val alu_issue_rd_def = alu_iq_.io.issue.bits.extra.rd_def
  val alu_result       = alu_.io.out.result

  rob_.io.lookup1.tag := alu_issue_tag
  val alu_rob_entry = rob_.io.lookup1.entry

  val alu_rd_val       = Mux(alu_rob_entry.rd_val_valid, alu_rob_entry.rd_val, alu_result)
  val alu_rd_val_valid = alu_rob_entry.rd_val_valid || (alu_rob_entry.wbSel === WBSel.WB_ALU)

  val alu_is_jalr    = alu_rob_entry.npcOp === NPCOpType.NPC_JALR
  val alu_mispredict = alu_is_jalr
  val alu_target_npc = Mux(alu_is_jalr, alu_result & ~1.U(addrBits.W), alu_rob_entry.pc + 4.U)

  val is_csr_wb        = alu_rob_entry.wbSel === WBSel.WB_CSR || alu_rob_entry.csr_wen
  val final_alu_result = Mux(is_csr_wb, alu_iq_.io.issue.bits.src1_v, alu_result)

  rob_.io.alu.valid             := alu_issue_valid
  rob_.io.alu.bits.tag          := alu_issue_tag
  rob_.io.alu.bits.alu_result   := final_alu_result
  rob_.io.alu.bits.rd_val       := alu_rd_val
  rob_.io.alu.bits.rd_val_valid := alu_rd_val_valid
  rob_.io.alu.bits.mispredict   := alu_mispredict
  rob_.io.alu.bits.target_npc   := alu_target_npc

  // --- BRU path ---
  bru_iq_.io.issue.ready := true.B

  bru_.io.in.rs1_v := bru_iq_.io.issue.bits.src1_v
  bru_.io.in.rs2_v := bru_iq_.io.issue.bits.src2_v
  bru_.io.in.op    := bru_iq_.io.issue.bits.extra.bruOp

  val bru_issue_valid = bru_iq_.io.issue.fire
  val bru_issue_tag   = bru_iq_.io.issue.bits.rob_tag
  val br_flag         = bru_.io.out.br_flag

  rob_.io.lookup2.tag := bru_issue_tag
  val bru_rob_entry = rob_.io.lookup2.entry

  val bru_mispredict = br_flag
  val bru_actual_npc = Mux(br_flag, bru_rob_entry.target_npc, bru_rob_entry.pc + 4.U)

  rob_.io.bru.valid      := bru_issue_valid
  rob_.io.bru.bits.tag        := bru_issue_tag
  rob_.io.bru.bits.mispredict := bru_mispredict
  rob_.io.bru.bits.actual_npc := bru_actual_npc

  // --- AGU path ---
  agu_iq_.io.issue.ready := true.B

  agu_.io.in.base   := agu_iq_.io.issue.bits.src1_v
  agu_.io.in.offset := agu_iq_.io.issue.bits.extra.imm

  rob_.io.agu.valid := agu_iq_.io.issue.fire
  rob_.io.agu.bits.tag   := agu_iq_.io.issue.bits.rob_tag
  rob_.io.agu.bits.addr  := agu_.io.out.addr
  rob_.io.agu.bits.wdata := agu_iq_.io.issue.bits.src2_v

  // --- CDB1 — ALU writeback broadcast ---
  cdb1.valid := alu_issue_valid && alu_rd_val_valid && alu_issue_rd_def && !flush
  cdb1.tag   := alu_issue_tag
  cdb1.value := alu_rd_val

  // ==========================================================
  // Stage 5 — Commit + Late Execute
  // ==========================================================
  object CommitState extends ChiselEnum {
    val idle, late_wait = Value
  }
  val commitStateQ = RegInit(CommitState.idle)

  val head       = rob_.io.commit.bits.entry
  val head_tag   = rob_.io.commit.bits.tag
  val head_valid = rob_.io.commit.valid

  val head_is_mem = head.mem.r_en || head.mem.w_en
  val head_is_csr = head.wbSel === WBSel.WB_CSR || head.csr_wen

  // CDB2 defaults
  cdb2.valid := false.B
  cdb2.tag   := head_tag
  cdb2.value := 0.U

  // Commit-time writeback to ROB defaults
  rob_.io.wb_commit.valid := false.B
  rob_.io.wb_commit.bits.tag   := head_tag
  rob_.io.wb_commit.bits.value := 0.U

  rob_.io.commit.ready := false.B

  // RFU write defaults
  rfu_.io.in.wen   := false.B
  rfu_.io.in.rd_i  := head.rd_idx
  rfu_.io.in.wdata := head.rd_val

  // RAT commit-clear defaults
  rat_.io.commit.en   := false.B
  rat_.io.commit.addr := head.rd_idx
  rat_.io.commit.tag  := head_tag

  // CSRU defaults
  csru_.io.late.req           := false.B
  csru_.io.addr               := head.imm(NRCSRbits - 1, 0)
  csru_.io.wop                := head.csrOp
  csru_.io.wen                := false.B
  csru_.io.wdata              := head.alu_result
  csru_.io.commit.xepc        := head.pc
  csru_.io.commit.xepc_wen    := false.B
  csru_.io.commit.xcause      := head.mcause
  csru_.io.commit.xcause_wen  := false.B
  csru_.io.commit.xtval       := head.xtval
  csru_.io.commit.xtval_wen   := false.B

  // LSU defaults
  lsu_.io.late.req := false.B
  lsu_.io.addr     := head.mem.addr
  lsu_.io.size     := head.mem.size
  lsu_.io.sign_ext := head.mem.sign_ext
  lsu_.io.r_en     := head.mem.r_en
  lsu_.io.w_en     := head.mem.w_en
  lsu_.io.wdata    := head.mem.wdata

  fence_i := false.B

  // Debug signals
  val commit_valid_dbg = RegInit(false.B)
  val commit_pc_dbg    = Reg(UInt(dataBits.W))
  val commit_dnpc_dbg  = Reg(UInt(dataBits.W))
  val commit_inst_dbg  = Reg(UInt(InstBits.W))
  commit_valid_dbg := false.B

  // ---- Commit state machine ----
  switch(commitStateQ) {
    is(CommitState.idle) {
      when(head_valid) {
        when(head.except_en) {
          csru_.io.commit.xepc_wen   := true.B
          csru_.io.commit.xcause_wen := true.B
          csru_.io.commit.xtval_wen  := true.B

          flush := true.B
          redirect.valid  := true.B
          redirect.target := csru_.io.xtvec

          rob_.io.commit.ready := true.B

          commit_valid_dbg := true.B
          commit_pc_dbg    := head.pc
          commit_dnpc_dbg  := csru_.io.xtvec
          commit_inst_dbg  := head.inst

        }.elsewhen(head_is_mem) {
          lsu_.io.late.req := true.B
          when(lsu_.io.late.done) {
            when(head.rd_def) {
              rfu_.io.in.wen   := true.B
              rfu_.io.in.wdata := lsu_.io.late.result
            }
            rob_.io.wb_commit.valid := head.rd_def
            rob_.io.wb_commit.bits.value := lsu_.io.late.result
            cdb2.valid := head.rd_def
            cdb2.value := lsu_.io.late.result

            rat_.io.commit.en  := head.rd_def
            rob_.io.commit.ready := true.B

            commit_valid_dbg := true.B
            commit_pc_dbg    := head.pc
            commit_dnpc_dbg  := head.pc + 4.U
            commit_inst_dbg  := head.inst
          }.otherwise {
            commitStateQ := CommitState.late_wait
          }

        }.elsewhen(head_is_csr) {
          csru_.io.late.req := true.B
          csru_.io.wen      := head.csr_wen
          val csr_rd = csru_.io.late.result

          when(head.rd_def) {
            rfu_.io.in.wen   := true.B
            rfu_.io.in.wdata := csr_rd
          }

          rob_.io.wb_commit.valid := head.rd_def
          rob_.io.wb_commit.bits.value := csr_rd
          cdb2.valid := head.rd_def
          cdb2.value := csr_rd

          rat_.io.commit.en := head.rd_def

          when(head.npcOp === NPCOpType.NPC_MRET) {
            flush := true.B
            redirect.valid  := true.B
            redirect.target := csru_.io.xepc
          }

          rob_.io.commit.ready := true.B

          commit_valid_dbg := true.B
          commit_pc_dbg    := head.pc
          commit_dnpc_dbg  := Mux(
            head.npcOp === NPCOpType.NPC_MRET,
            csru_.io.xepc,
            head.pc + 4.U
          )
          commit_inst_dbg := head.inst

        }.otherwise {
          when(head.rd_def) {
            rfu_.io.in.wen   := true.B
            rfu_.io.in.wdata := head.rd_val
          }

          rat_.io.commit.en := head.rd_def

          when(head.mispredict) {
            flush := true.B
            redirect.valid  := true.B
            redirect.target := head.target_npc
          }

          rob_.io.commit.ready := true.B

          commit_valid_dbg := true.B
          commit_pc_dbg    := head.pc
          commit_dnpc_dbg  := Mux(head.mispredict, head.target_npc, head.pc + 4.U)
          commit_inst_dbg  := head.inst
        }
      }
    }

    is(CommitState.late_wait) {
      lsu_.io.late.req := true.B
      when(lsu_.io.late.done) {
        when(head.rd_def) {
          rfu_.io.in.wen   := true.B
          rfu_.io.in.wdata := lsu_.io.late.result
        }

        rob_.io.wb_commit.valid := head.rd_def
        rob_.io.wb_commit.bits.value := lsu_.io.late.result
        cdb2.valid := head.rd_def
        cdb2.value := lsu_.io.late.result

        rat_.io.commit.en  := head.rd_def
        rob_.io.commit.ready := true.B
        commitStateQ       := CommitState.idle

        commit_valid_dbg := true.B
        commit_pc_dbg    := head.pc
        commit_dnpc_dbg  := head.pc + 4.U
        commit_inst_dbg  := head.inst
      }
    }
  }

  // ==========================================================
  // Debug probe
  // ==========================================================
  val dbg = Wire(new DebugBundle)
  dbg.valid  := commit_valid_dbg
  dbg.pc     := commit_pc_dbg
  dbg.dnpc   := commit_dnpc_dbg
  dbg.inst   := commit_inst_dbg
  dbg.isMMIO := false.B
  dbg.gpr    := VecInit((0 until NRReg).map(i => read(rfu_.io.probe)(i)))
  dbg.csr    := read(csru_.io.probe)
  define(probe, ProbeValue(dbg))
}
