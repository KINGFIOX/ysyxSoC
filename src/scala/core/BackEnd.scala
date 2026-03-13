package ysyx.core

import chisel3._
import chisel3.util._
import chisel3.probe.{define, Probe, ProbeValue, read}

import ysyx.core.common.{HasCSRParameter, HasCoreParameter, HasRegFileParameter}
import ysyx.core.component._
import freechips.rocketchip.amba.axi4._
import freechips.rocketchip.util._
import ysyx.core.common.NPCModule

class BackEnd(axiParams: AXI4BundleParameters) extends NPCModule {

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
  val cu_ = Module(new CU)
  val igu_ = Module(new IGU)
  val rfu_ = Module(new RFU)
  val alu_ = Module(new ALU)
  val bru_ = Module(new BRU)
  val rob_ = Module(new Rob)
  val rat_ = Module(new RAT)
  val alu_iq_ = Module(new ALUIssueQueue)
  val bru_iq_ = Module(new BRUIssueQueue)
  val agu_iq_ = Module(new AGUIssueQueue)
  val agu_ = Module(new AGU)
  val csru_ = Module(new CSRU)

  val dcache_load = Module(new LoadUnit(axiParams, 1))
  val dcache_store = Module(new StoreUnit(axiParams, 2))

  // ==========================================================
  // AXI connections
  // ==========================================================
  dcache_load.ar <> dcache.ar
  dcache_load.r <> dcache.r
  dcache_store.aw <> dcache.aw
  dcache_store.w <> dcache.w
  dcache_store.b <> dcache.b

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
  // Decode stage (combinational from instruction input)
  // ==========================================================
  val ifu_out = io.in.bits
  val ifu_valid = io.in.valid

  cu_.io.in.inst := ifu_out.inst
  igu_.io.in.inst_31_7 := ifu_out.inst(31, 7)
  igu_.io.in.immType := cu_.io.out.immType

  val rs1_idx = ifu_out.inst(19, 15)
  val rs2_idx = ifu_out.inst(24, 20)
  val rd_idx = ifu_out.inst(11, 7)
  val imm = igu_.io.out.imm
  val dispatch_pc = ifu_out.pc

  // ==========================================================
  // Instruction classification for dispatch routing
  // ==========================================================
  val npcOp = cu_.io.out.npcOp
  val aluSel1 = cu_.io.out.aluSel1
  val aluSel2 = cu_.io.out.aluSel2

  val is_jal = npcOp === NPCOpType.NPC_JAL
  val is_mret = npcOp === NPCOpType.NPC_MRET
  val is_lui = aluSel1 === ALUOp1Sel.OP1_ZERO
  val is_auipc = aluSel1 === ALUOp1Sel.OP1_PC && npcOp === NPCOpType.NPC_4
  val is_branch = npcOp === NPCOpType.NPC_BR

  val ifu_except_en = ifu_out.exceptionEn
  val cu_except_en = cu_.io.out.exceptionEn
  val dispatch_except_en = ifu_except_en || cu_except_en

  val is_mem = cu_.io.out.mem.r_en || cu_.io.out.mem.w_en

  val dispatch_resolved = is_jal || is_mret || is_lui || is_auipc
  val skip_iq = dispatch_except_en || dispatch_resolved
  val go_to_alu = !skip_iq && !is_branch && !is_mem
  val go_to_bru = is_branch && !skip_iq
  val go_to_agu = is_mem && !skip_iq

  // ==========================================================
  // CDB wires (declared early for dispatch bypass)
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
  // Rename — RAT + RFU + ROB forward + CDB bypass
  // ==========================================================
  rat_.io.read1.addr := rs1_idx
  rat_.io.read2.addr := rs2_idx

  rfu_.io.in.rs1_i := rs1_idx
  rfu_.io.in.rs2_i := rs2_idx

  val needs_rs1 = (aluSel1 === ALUOp1Sel.OP1_RS1) || is_branch
  val needs_rs2 =
    (aluSel2 === ALUOp2Sel.OP2_RS2) || is_branch || cu_.io.out.mem.w_en

  // --- Source 1 resolution ---
  val src1_ready = Wire(Bool())
  val src1_val = Wire(UInt(dataBits.W))
  val src1_tag = Wire(UInt(robEntryBits.W))

  rob_.io.fwd1.tag := rat_.io.read1.tag

  when(!needs_rs1) {
    src1_ready := true.B
    src1_val := 0.U
    src1_tag := 0.U
  }.elsewhen(!rat_.io.read1.busy) {
    src1_ready := true.B
    src1_val := rfu_.io.out.rs1_v
    src1_tag := 0.U
  }.otherwise {
    val fwd_tag = rat_.io.read1.tag
    val fwd_rdy = rob_.io.fwd1.ready ||
      (cdb1.valid && cdb1.tag === fwd_tag) ||
      (cdb2.valid && cdb2.tag === fwd_tag)
    val fwd_val = MuxCase(
      rob_.io.fwd1.value,
      Seq(
        (cdb1.valid && cdb1.tag === fwd_tag) -> cdb1.value,
        (cdb2.valid && cdb2.tag === fwd_tag) -> cdb2.value
      )
    )
    src1_ready := fwd_rdy
    src1_val := fwd_val
    src1_tag := fwd_tag
  }

  // --- Source 2 resolution ---
  val src2_ready = Wire(Bool())
  val src2_val = Wire(UInt(dataBits.W))
  val src2_tag = Wire(UInt(robEntryBits.W))

  rob_.io.fwd2.tag := rat_.io.read2.tag

  when(!needs_rs2) {
    src2_ready := true.B
    src2_val := 0.U
    src2_tag := 0.U
  }.elsewhen(!rat_.io.read2.busy) {
    src2_ready := true.B
    src2_val := rfu_.io.out.rs2_v
    src2_tag := 0.U
  }.otherwise {
    val fwd_tag = rat_.io.read2.tag
    val fwd_rdy = rob_.io.fwd2.ready ||
      (cdb1.valid && cdb1.tag === fwd_tag) ||
      (cdb2.valid && cdb2.tag === fwd_tag)
    val fwd_val = MuxCase(
      rob_.io.fwd2.value,
      Seq(
        (cdb1.valid && cdb1.tag === fwd_tag) -> cdb1.value,
        (cdb2.valid && cdb2.tag === fwd_tag) -> cdb2.value
      )
    )
    src2_ready := fwd_rdy
    src2_val := fwd_val
    src2_tag := fwd_tag
  }

  // ==========================================================
  // Exception mcause mapping
  // ==========================================================
  val dispatch_mcause = MuxCase(
    0.U,
    Seq(
      ifu_except_en -> MuxLookup(ifu_out.exception, 0.U)(
        Seq(
          IFUExceptionType.ifu_INSTRUCTION_ADDRESS_MISALIGNED -> 0.U,
          IFUExceptionType.ifu_INSTRUCTION_ACCESS_FAULT -> 1.U,
          IFUExceptionType.ifu_INSTRUCTION_PAGE_FAULT -> 12.U
        )
      ),
      cu_except_en -> MuxLookup(cu_.io.out.exception, 0.U)(
        Seq(
          CUExceptionType.cu_ILLEGAL_INSTRUCTION -> 2.U,
          CUExceptionType.cu_BREAKPOINT -> 3.U,
          CUExceptionType.cu_ECALL_FROM_U_MODE -> 8.U,
          CUExceptionType.cu_ECALL_FROM_S_MODE -> 9.U,
          CUExceptionType.cu_ECALL_FROM_M_MODE -> 11.U
        )
      )
    )
  )

  val dispatch_mtval = Mux(ifu_except_en, ifu_out.mtval, cu_.io.out.mtval)

  // ==========================================================
  // Dispatch-resolved value computation
  // ==========================================================
  val disp_rd_val = MuxCase(
    0.U,
    Seq(
      is_jal -> (dispatch_pc + 4.U),
      is_lui -> imm,
      is_auipc -> (dispatch_pc + imm)
    )
  )
  val disp_rd_val_valid = is_jal || is_lui || is_auipc
  val disp_mispredict = is_jal || is_mret
  val disp_target_npc = MuxCase(
    0.U,
    Seq(
      is_jal -> (dispatch_pc + imm),
      is_branch -> (dispatch_pc + imm)
    )
  )

  // ==========================================================
  // Dispatch — enqueue to ROB + IQ, update RAT
  // ==========================================================
  val iq_ready = MuxCase(
    true.B,
    Seq(
      go_to_alu -> alu_iq_.io.enq.ready,
      go_to_bru -> bru_iq_.io.enq.ready,
      go_to_agu -> agu_iq_.io.enq.ready
    )
  )
  val can_dispatch = ifu_valid && rob_.io.enq.ready && iq_ready && !flush
  io.in.ready := rob_.io.enq.ready && iq_ready && !flush

  // --- ROB enqueue ---
  rob_.io.enq.valid := can_dispatch

  val rob_enq = rob_.io.enq.bits
  rob_enq.wbSel := cu_.io.out.wbSel
  rob_enq.mem := cu_.io.out.mem
  rob_enq.csrOp := cu_.io.out.csrOp
  rob_enq.csrWen := cu_.io.out.csrWen
  rob_enq.npcOp := npcOp
  rob_enq.pc := dispatch_pc
  rob_enq.inst := ifu_out.inst
  rob_enq.imm := imm
  rob_enq.rd_idx := rd_idx
  rob_enq.rd_def := cu_.io.out.rfWen && (rd_idx =/= 0.U)
  rob_enq.except_en := dispatch_except_en
  rob_enq.mcause := dispatch_mcause
  rob_enq.mtval := dispatch_mtval
  rob_enq.dispatch_executed := dispatch_resolved
  rob_enq.rd_val := disp_rd_val
  rob_enq.rd_val_valid := disp_rd_val_valid
  rob_enq.mispredict := disp_mispredict
  rob_enq.target_npc := disp_target_npc

  // --- ALU IQ enqueue ---
  alu_iq_.io.enq.valid := can_dispatch && go_to_alu

  val alu_enq = alu_iq_.io.enq.data
  alu_enq.src1_val := src1_val
  alu_enq.src1_tag := src1_tag
  alu_enq.src1_ready := src1_ready

  val imm_as_src2 = aluSel2 === ALUOp2Sel.OP2_IMM
  alu_enq.src2_val := Mux(imm_as_src2, imm, src2_val)
  alu_enq.src2_tag := Mux(imm_as_src2, 0.U, src2_tag)
  alu_enq.src2_ready := Mux(imm_as_src2, true.B, src2_ready)

  alu_enq.aluOp := cu_.io.out.aluOp
  alu_enq.rob_tag := rob_.io.enq_tag // def
  alu_enq.rd_def := cu_.io.out.rfWen && (rd_idx =/= 0.U)

  // --- BRU IQ enqueue ---
  bru_iq_.io.enq.valid := can_dispatch && go_to_bru

  val bru_enq = bru_iq_.io.enq.data
  bru_enq.rs1_val := src1_val
  bru_enq.rs1_tag := src1_tag
  bru_enq.rs1_ready := src1_ready
  bru_enq.rs2_val := src2_val
  bru_enq.rs2_tag := src2_tag
  bru_enq.rs2_ready := src2_ready
  bru_enq.bruOp := cu_.io.out.bruOp
  bru_enq.rob_tag := rob_.io.enq_tag

  // --- AGU IQ enqueue ---
  agu_iq_.io.enq.valid := can_dispatch && go_to_agu

  val agu_enq = agu_iq_.io.enq.data
  agu_enq.base_val   := src1_val
  agu_enq.base_tag   := src1_tag
  agu_enq.base_ready := src1_ready
  val is_load = cu_.io.out.mem.r_en
  agu_enq.wdata_val   := Mux(is_load, 0.U, src2_val)
  agu_enq.wdata_tag   := Mux(is_load, 0.U, src2_tag)
  agu_enq.wdata_ready := Mux(is_load, true.B, src2_ready)
  agu_enq.imm         := imm
  agu_enq.rob_tag     := rob_.io.enq_tag

  // --- RAT update ---
  val should_rename = can_dispatch && cu_.io.out.rfWen && (rd_idx =/= 0.U) && !dispatch_except_en
  rat_.io.write.en := should_rename
  rat_.io.write.addr := rd_idx
  rat_.io.write.tag := rob_.io.enq_tag

  // ==========================================================
  // ALU Issue -> Execute -> Writeback
  // ==========================================================
  alu_.io.in.op1 := alu_iq_.io.issue.op1
  alu_.io.in.op2 := alu_iq_.io.issue.op2
  alu_.io.in.aluOp := alu_iq_.io.issue.aluOp

  val alu_issue_valid = alu_iq_.io.issue.valid
  val alu_issue_tag = alu_iq_.io.issue.rob_tag
  val alu_issue_rd_def = alu_iq_.io.issue.rd_def
  val alu_result = alu_.io.out.result

  rob_.io.lookup1.tag := alu_issue_tag
  val alu_rob_entry = rob_.io.lookup1.entry

  val alu_rd_val = Mux(alu_rob_entry.rd_val_valid, alu_rob_entry.rd_val, alu_result)
  val alu_rd_val_valid = alu_rob_entry.rd_val_valid || (alu_rob_entry.wbSel === WBSel.WB_ALU)

  val alu_is_jalr = alu_rob_entry.npcOp === NPCOpType.NPC_JALR
  val alu_mispredict = alu_is_jalr
  val alu_target_npc = Mux(alu_is_jalr, alu_result & ~1.U(addrBits.W), alu_rob_entry.pc + 4.U)

  val is_csr_wb = alu_rob_entry.wbSel === WBSel.WB_CSR || alu_rob_entry.csr_wen
  val final_alu_result = Mux(is_csr_wb, alu_iq_.io.issue.src1_v, alu_result)

  rob_.io.wb.valid := alu_issue_valid
  rob_.io.wb.tag := alu_issue_tag
  rob_.io.wb.alu_result := final_alu_result
  rob_.io.wb.rd_val := alu_rd_val
  rob_.io.wb.rd_val_valid := alu_rd_val_valid
  rob_.io.wb.mispredict := alu_mispredict
  rob_.io.wb.target_npc := alu_target_npc

  // ==========================================================
  // BRU Issue -> Execute -> Writeback
  // ==========================================================
  bru_.io.in.rs1_v := bru_iq_.io.issue.rs1_v
  bru_.io.in.rs2_v := bru_iq_.io.issue.rs2_v
  bru_.io.in.op := bru_iq_.io.issue.bruOp

  val bru_issue_valid = bru_iq_.io.issue.valid
  val bru_issue_tag = bru_iq_.io.issue.rob_tag
  val br_flag = bru_.io.out.br_flag

  rob_.io.lookup2.tag := bru_issue_tag
  val bru_rob_entry = rob_.io.lookup2.entry

  val bru_mispredict = br_flag
  val bru_actual_npc = Mux(br_flag, bru_rob_entry.target_npc, bru_rob_entry.pc + 4.U)

  rob_.io.wb2.valid := bru_issue_valid
  rob_.io.wb2.tag := bru_issue_tag
  rob_.io.wb2.mispredict := bru_mispredict
  rob_.io.wb2.actual_npc := bru_actual_npc

  // ==========================================================
  // AGU Issue -> Execute -> Writeback to ROB
  // ==========================================================
  agu_.io.in.base   := agu_iq_.io.issue.base_v
  agu_.io.in.offset := agu_iq_.io.issue.imm

  rob_.io.wb_agu.valid := agu_iq_.io.issue.valid
  rob_.io.wb_agu.tag   := agu_iq_.io.issue.rob_tag
  rob_.io.wb_agu.addr  := agu_.io.out.addr
  rob_.io.wb_agu.wdata := agu_iq_.io.issue.wdata_v

  // ==========================================================
  // CDB1 — ALU writeback broadcast
  // ==========================================================
  cdb1.valid := alu_issue_valid && alu_rd_val_valid && alu_issue_rd_def && !flush
  cdb1.tag := alu_issue_tag
  cdb1.value := alu_rd_val

  // ==========================================================
  // Commit state machine
  // ==========================================================
  object CommitState extends ChiselEnum {
    val idle, lsu_req, lsu_wait = Value
  }
  val commitStateQ = RegInit(CommitState.idle)

  val head = rob_.io.commit.entry
  val head_tag = rob_.io.commit.tag
  val head_valid = rob_.io.commit.valid

  // CDB2 defaults
  cdb2.valid := false.B
  cdb2.tag := head_tag
  cdb2.value := 0.U

  // Commit-time writeback to ROB
  rob_.io.commitWb.valid := false.B
  rob_.io.commitWb.tag := head_tag
  rob_.io.commitWb.value := 0.U

  rob_.io.commit.deq := false.B

  // RFU write defaults
  rfu_.io.in.wen := false.B
  rfu_.io.in.rd_i := head.rd_idx
  rfu_.io.in.wdata := head.rd_val

  // RAT commit-clear defaults
  rat_.io.commit.en := false.B
  rat_.io.commit.addr := head.rd_idx
  rat_.io.commit.tag := head_tag

  // CSRU defaults
  csru_.io.addr := head.imm(NRCSRbits - 1, 0)
  csru_.io.wop := head.csrOp
  csru_.io.wen := false.B
  csru_.io.wdata := head.alu_result
  csru_.io.commit.xepc := head.pc
  csru_.io.commit.xepc_wen := false.B
  csru_.io.commit.xcause := head.mcause
  csru_.io.commit.xcause_wen := false.B
  csru_.io.commit.xtval := head.xtval
  csru_.io.commit.xtval_wen := false.B

  // LSU defaults
  dcache_load.in.req := false.B
  dcache_load.in.wr := false.B
  dcache_load.in.size := head.mem.size
  dcache_load.in.addr := head.mem.addr
  dcache_load.in.wstrb := 0.U
  dcache_load.in.wdata := 0.U

  val store_wstrb = MuxLookup(head.mem.size, "b1111".U)(
    Seq(
      0.U -> (1.U(4.W) << head.mem.addr(1, 0)),
      1.U -> (3.U(4.W) << (head.mem.addr(1, 0) & "b10".U)),
      2.U -> "b1111".U(4.W)
    )
  )
  val store_wdata = MuxLookup(head.mem.size, head.mem.wdata)(
    Seq(
      0.U -> Fill(4, head.mem.wdata(7, 0)),
      1.U -> Fill(2, head.mem.wdata(15, 0)),
      2.U -> head.mem.wdata
    )
  )

  dcache_store.in.req := false.B
  dcache_store.in.wr := true.B
  dcache_store.in.size := head.mem.size
  dcache_store.in.addr := head.mem.addr
  dcache_store.in.wstrb := store_wstrb
  dcache_store.in.wdata := store_wdata

  fence_i := false.B

  // load data extraction
  val load_raw = dcache_load.in.rdata
  val load_byte = MuxLookup(head.mem.addr(1, 0), load_raw(7, 0))(
    Seq(
      0.U -> load_raw(7, 0),
      1.U -> load_raw(15, 8),
      2.U -> load_raw(23, 16),
      3.U -> load_raw(31, 24)
    )
  )
  val load_half = Mux(head.mem.addr(1), load_raw(31, 16), load_raw(15, 0))
  val load_final = MuxLookup(head.mem.size, load_raw)(
    Seq(
      0.U -> Mux(
        head.mem.sign_ext,
        SignExt(load_byte, 32),
        ZeroExt(load_byte, 32)
      ),
      1.U -> Mux(
        head.mem.sign_ext,
        SignExt(load_half, 32),
        ZeroExt(load_half, 32)
      ),
      2.U -> load_raw
    )
  )

  // ---- Commit debug signals for probe ----
  val commit_valid_dbg = RegInit(false.B)
  val commit_pc_dbg = Reg(UInt(dataBits.W))
  val commit_dnpc_dbg = Reg(UInt(dataBits.W))
  val commit_inst_dbg = Reg(UInt(InstBits.W))

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

          rob_.io.commit.deq := true.B

          commit_valid_dbg := true.B
          commit_pc_dbg := head.pc
          commit_dnpc_dbg := csru_.io.xtvec
          commit_inst_dbg := head.inst

        }.elsewhen(head.mem.r_en) {
          dcache_load.in.req := true.B
          commitStateQ := CommitState.lsu_req

        }.elsewhen(head.mem.w_en) {
          dcache_store.in.req := true.B
          commitStateQ := CommitState.lsu_req

        }.elsewhen(head.wbSel === WBSel.WB_CSR || head.csr_wen) {
          csru_.io.wen := head.csr_wen
          val csr_rd = csru_.io.rdata

          when(head.rd_def) {
            rfu_.io.in.wen := true.B
            rfu_.io.in.wdata := csr_rd
          }

          rob_.io.commitWb.valid := head.rd_def
          rob_.io.commitWb.value := csr_rd
          cdb2.valid := head.rd_def
          cdb2.value := csr_rd

          rat_.io.commit.en := head.rd_def

          when(head.npcOp === NPCOpType.NPC_MRET) {
            flush := true.B
            redirect.valid := true.B
            redirect.target := csru_.io.xepc
          }

          rob_.io.commit.deq := true.B

          commit_valid_dbg := true.B
          commit_pc_dbg := head.pc
          commit_dnpc_dbg := Mux(
            head.npcOp === NPCOpType.NPC_MRET,
            csru_.io.xepc,
            head.pc + 4.U
          )
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

          rob_.io.commit.deq := true.B

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

    is(CommitState.lsu_req) {
      when(head.mem.r_en) {
        dcache_load.in.req := true.B
        when(dcache_load.in.addr_ok) {
          commitStateQ := CommitState.lsu_wait
        }
      }.otherwise {
        dcache_store.in.req := true.B
        when(dcache_store.in.addr_ok) {
          commitStateQ := CommitState.lsu_wait
        }
      }
    }

    is(CommitState.lsu_wait) {
      when(head.mem.r_en && dcache_load.in.data_ok) {
        when(head.rd_def) {
          rfu_.io.in.wen := true.B
          rfu_.io.in.wdata := load_final
        }
        rob_.io.commitWb.valid := head.rd_def
        rob_.io.commitWb.value := load_final
        cdb2.valid := head.rd_def
        cdb2.value := load_final

        rat_.io.commit.en := head.rd_def
        rob_.io.commit.deq := true.B
        commitStateQ := CommitState.idle

        commit_valid_dbg := true.B
        commit_pc_dbg := head.pc
        commit_dnpc_dbg := head.pc + 4.U
        commit_inst_dbg := head.inst

      }.elsewhen(head.mem.w_en && dcache_store.in.data_ok) {
        rat_.io.commit.en := false.B
        rob_.io.commit.deq := true.B
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
