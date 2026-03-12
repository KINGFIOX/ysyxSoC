package ysyx.core

import chisel3._
import chisel3.util._
import chisel3.probe.{define, Probe, ProbeValue, read}

import ysyx.core.common.{HasCSRParameter, HasCoreParameter, HasRegFileParameter}
import ysyx.core.component._
import freechips.rocketchip.amba.axi4._
import freechips.rocketchip.util._
import ysyx.CPUAXI4BundleParameters
import ysyx.core.common.NPCModule

class DebugBundle
    extends Bundle
    with HasCoreParameter
    with HasRegFileParameter {
  val valid = Bool()
  val pc = UInt(dataBits.W)
  val dnpc = UInt(dataBits.W)
  val inst = UInt(InstBits.W)
  val isMMIO = Bool()
  val gpr = Vec(NRReg, UInt(dataBits.W))
  val csr = new CSRUDebugBundle
}

class NPCCore(axiParams: AXI4BundleParameters) extends NPCModule {

  val icache = IO(AXI4Bundle(axiParams))
  val dcache = IO(AXI4Bundle(axiParams))
  val probe = IO(Output(Probe(new DebugBundle)))
  val interrupt = IO(Input(Bool()))
  val fence_i = IO(Output(Bool()))

  // ==========================================================
  // Sub-modules
  // ==========================================================
  val ifu_ = Module(new IFU(axiParams))
  val cu_ = Module(new CU)
  val igu_ = Module(new IGU)
  val rfu_ = Module(new RFU)
  val alu_ = Module(new ALU)
  val bru_ = Module(new BRU)
  val rob_ = Module(new Rob)
  val rat_ = Module(new RAT)
  val iq_ = Module(new IssueQueue)
  val csru_ = Module(new CSRU)

  val dcache_load = Module(new LoadUnit(axiParams, 1))
  val dcache_store = Module(new StoreUnit(axiParams, 1))

  // ==========================================================
  // AXI connections
  // ==========================================================
  ifu_.icache <> icache

  dcache_load.ar <> dcache.ar
  dcache_load.r <> dcache.r
  dcache_store.aw <> dcache.aw
  dcache_store.w <> dcache.w
  dcache_store.b <> dcache.b

  // ==========================================================
  // Flush / redirect wiring
  // ==========================================================
  val flush = Wire(Bool())
  val redirect = Wire(new RedirectBundle)

  ifu_.io.redirect := redirect
  rob_.io.flush := flush
  rat_.io.flush := flush
  iq_.io.flush := flush

  // defaults
  flush := false.B
  redirect.valid := false.B
  redirect.target := 0.U

  // ==========================================================
  // Decode stage (combinational from IFU output)
  // ==========================================================
  val ifu_out = ifu_.io.out.bits
  val ifu_valid = ifu_.io.out.valid

  cu_.io.in.inst := ifu_out.inst
  igu_.io.in.inst_31_7 := ifu_out.inst(31, 7)
  igu_.io.in.immType := cu_.io.out.immType

  val rs1_idx = ifu_out.inst(19, 15)
  val rs2_idx = ifu_out.inst(24, 20)
  val rd_idx = ifu_out.inst(11, 7)
  val imm = igu_.io.out.imm

  // ==========================================================
  // CDB wires (declared early for dispatch bypass)
  // ==========================================================
  val cdb1 = Wire(new CDBBundle)
  val cdb2 = Wire(new CDBBundle)

  iq_.io.cdb1 := cdb1
  iq_.io.cdb2 := cdb2

  // ==========================================================
  // Rename — RAT + RFU + ROB forward + CDB bypass
  // ==========================================================
  rat_.io.read1.addr := rs1_idx
  rat_.io.read2.addr := rs2_idx

  rfu_.io.in.rs1_i := rs1_idx
  rfu_.io.in.rs2_i := rs2_idx

  val needs_rs1 = (cu_.io.out.aluSel1 === ALUOp1Sel.OP1_RS1) ||
    (cu_.io.out.npcOp === NPCOpType.NPC_BR)
  val needs_rs2 = (cu_.io.out.aluSel2 === ALUOp2Sel.OP2_RS2) ||
    (cu_.io.out.npcOp === NPCOpType.NPC_BR) ||
    cu_.io.out.mem.w_en

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
  // Exception from IFU / CU  →  mcause mapping
  // ==========================================================
  val ifu_except_en = ifu_out.exceptionEn
  val cu_except_en = cu_.io.out.exceptionEn

  val dispatch_except_en = ifu_except_en || cu_except_en

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
  // Dispatch — enqueue to ROB + IQ, update RAT
  // ==========================================================
  val can_dispatch = ifu_valid && rob_.io.enq.ready &&
    (iq_.io.enq.ready || dispatch_except_en) && !flush
  ifu_.io.out.ready := rob_.io.enq.ready &&
    (iq_.io.enq.ready || dispatch_except_en) && !flush

  // --- ROB enqueue ---
  rob_.io.enq.valid := can_dispatch

  val rob_enq = rob_.io.enq.data
  rob_enq.wbSel := cu_.io.out.wbSel
  rob_enq.mem := cu_.io.out.mem
  rob_enq.csrOp := cu_.io.out.csrOp
  rob_enq.csrWen := cu_.io.out.csrWen
  rob_enq.rfWen := cu_.io.out.rfWen
  rob_enq.npcOp := cu_.io.out.npcOp
  rob_enq.pc := ifu_out.pc
  rob_enq.inst := ifu_out.inst
  rob_enq.imm := imm
  rob_enq.rd_idx := rd_idx
  rob_enq.rd_def := cu_.io.out.rfWen && (rd_idx =/= 0.U)
  rob_enq.except_en := dispatch_except_en
  rob_enq.mcause := dispatch_mcause
  rob_enq.xtval := dispatch_mtval

  // --- IQ enqueue (skip for exception instructions) ---
  iq_.io.enq.valid := can_dispatch && !dispatch_except_en

  val iq_enq = iq_.io.enq.data
  iq_enq.rs1_val := src1_val
  iq_enq.rs1_tag := src1_tag
  iq_enq.rs1_ready := src1_ready
  iq_enq.rs2_val := src2_val
  iq_enq.rs2_tag := src2_tag
  iq_enq.rs2_ready := src2_ready
  iq_enq.aluOp := cu_.io.out.aluOp
  iq_enq.aluSel1 := cu_.io.out.aluSel1
  iq_enq.aluSel2 := cu_.io.out.aluSel2
  iq_enq.npcOp := cu_.io.out.npcOp
  iq_enq.bruOp := cu_.io.out.bruOp
  iq_enq.wbSel := cu_.io.out.wbSel
  iq_enq.imm := imm
  iq_enq.pc := ifu_out.pc
  iq_enq.rob_tag := rob_.io.enq.tag
  iq_enq.rd_def := cu_.io.out.rfWen && (rd_idx =/= 0.U)

  // --- RAT update ---
  val should_rename = can_dispatch && cu_.io.out.rfWen &&
    (rd_idx =/= 0.U) && !dispatch_except_en
  rat_.io.write.en := should_rename
  rat_.io.write.addr := rd_idx
  rat_.io.write.tag := rob_.io.enq.tag

  // ==========================================================
  // Issue → ALU + BRU (from IQ)
  // ==========================================================
  alu_.io.in.op1 := iq_.io.issue.op1
  alu_.io.in.op2 := iq_.io.issue.op2
  alu_.io.in.aluOp := iq_.io.issue.aluOp

  bru_.io.in.rs1_v := iq_.io.issue.rs1_v
  bru_.io.in.rs2_v := iq_.io.issue.rs2_v
  bru_.io.in.op := iq_.io.issue.bruOp

  // ==========================================================
  // Writeback — compute results in NPCCore, write to ROB
  // ==========================================================
  val issue_valid = iq_.io.issue.valid
  val issue_npcOp = iq_.io.issue.npcOp
  val issue_wbSel = iq_.io.issue.wbSel
  val issue_pc = iq_.io.issue.pc
  val issue_tag = iq_.io.issue.rob_tag
  val issue_rd_def = iq_.io.issue.rd_def
  val alu_result = alu_.io.out.result
  val br_flag = bru_.io.out.br_flag

  val wb_rd_val = MuxCase(
    0.U,
    Seq(
      (issue_wbSel === WBSel.WB_ALU) -> alu_result,
      (issue_wbSel === WBSel.WB_PC4) -> (issue_pc + 4.U)
    )
  )
  val wb_rd_val_valid = (issue_wbSel === WBSel.WB_ALU) ||
    (issue_wbSel === WBSel.WB_PC4)

  val wb_mispredict = MuxLookup(issue_npcOp, false.B)(
    Seq(
      NPCOpType.NPC_4 -> false.B,
      NPCOpType.NPC_BR -> br_flag,
      NPCOpType.NPC_JAL -> true.B,
      NPCOpType.NPC_JALR -> true.B,
      NPCOpType.NPC_MRET -> true.B
    )
  )
  val wb_actual_npc = MuxLookup(issue_npcOp, (issue_pc + 4.U))(
    Seq(
      NPCOpType.NPC_4 -> (issue_pc + 4.U),
      NPCOpType.NPC_BR -> Mux(br_flag, alu_result, issue_pc + 4.U),
      NPCOpType.NPC_JAL -> alu_result,
      NPCOpType.NPC_JALR -> (alu_result & ~1.U(addrBits.W))
    )
  )

  rob_.io.wb.valid := issue_valid
  rob_.io.wb.tag := issue_tag
  rob_.io.wb.alu_result := alu_result
  rob_.io.wb.rd_val := wb_rd_val
  rob_.io.wb.rd_val_valid := wb_rd_val_valid
  rob_.io.wb.rs1_val := iq_.io.issue.rs1_v
  rob_.io.wb.rs2_val := iq_.io.issue.rs2_v
  rob_.io.wb.mispredict := wb_mispredict
  rob_.io.wb.actual_npc := wb_actual_npc

  // ==========================================================
  // CDB1 — ALU writeback broadcast to IQ
  // ==========================================================
  cdb1.valid := issue_valid && wb_rd_val_valid && issue_rd_def && !flush
  cdb1.tag := issue_tag
  cdb1.value := wb_rd_val

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

  // CDB2 defaults (commit-time writeback to IQ)
  cdb2.valid := false.B
  cdb2.tag := head_tag
  cdb2.value := 0.U

  // Commit-time writeback to ROB
  rob_.io.commitWb.valid := false.B
  rob_.io.commitWb.tag := head_tag
  rob_.io.commitWb.value := 0.U

  // ROB dequeue default
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
  csru_.io.wdata := head.rs1_val
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
  dcache_load.in.addr := head.alu_result
  dcache_load.in.wstrb := 0.U
  dcache_load.in.wdata := 0.U

  val store_wstrb = MuxLookup(head.mem.size, "b1111".U)(
    Seq(
      0.U -> (1.U(4.W) << head.alu_result(1, 0)),
      1.U -> (3.U(4.W) << (head.alu_result(1, 0) & "b10".U)),
      2.U -> "b1111".U(4.W)
    )
  )
  val store_wdata = MuxLookup(head.mem.size, head.rs2_val)(
    Seq(
      0.U -> Fill(4, head.rs2_val(7, 0)),
      1.U -> Fill(2, head.rs2_val(15, 0)),
      2.U -> head.rs2_val
    )
  )

  dcache_store.in.req := false.B
  dcache_store.in.wr := true.B
  dcache_store.in.size := head.mem.size
  dcache_store.in.addr := head.alu_result
  dcache_store.in.wstrb := store_wstrb
  dcache_store.in.wdata := store_wdata

  // fence_i default
  fence_i := false.B

  // load data extraction
  val load_raw = dcache_load.in.rdata
  val load_byte = MuxLookup(head.alu_result(1, 0), load_raw(7, 0))(
    Seq(
      0.U -> load_raw(7, 0),
      1.U -> load_raw(15, 8),
      2.U -> load_raw(23, 16),
      3.U -> load_raw(31, 24)
    )
  )
  val load_half = Mux(head.alu_result(1), load_raw(31, 16), load_raw(15, 0))
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

        }.elsewhen(head.wbSel === WBSel.WB_CSR || head.csrWen) {
          csru_.io.wen := head.csrWen
          val csr_rd = csru_.io.rdata

          when(head.rd_def && head.rfWen) {
            rfu_.io.in.wen := true.B
            rfu_.io.in.wdata := csr_rd
          }

          rob_.io.commitWb.valid := head.rd_def
          rob_.io.commitWb.value := csr_rd
          cdb2.valid := head.rd_def
          cdb2.value := csr_rd

          rat_.io.commit.en := head.rd_def && head.rfWen

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
          when(head.rd_def && head.rfWen) {
            rfu_.io.in.wen := true.B
            rfu_.io.in.wdata := head.rd_val
          }

          rat_.io.commit.en := head.rd_def && head.rfWen

          when(head.mispredict) {
            flush := true.B
            redirect.valid := true.B
            redirect.target := head.actual_npc
          }

          rob_.io.commit.deq := true.B

          commit_valid_dbg := true.B
          commit_pc_dbg := head.pc
          commit_dnpc_dbg := Mux(
            head.mispredict,
            head.actual_npc,
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
        when(head.rd_def && head.rfWen) {
          rfu_.io.in.wen := true.B
          rfu_.io.in.wdata := load_final
        }
        rob_.io.commitWb.valid := head.rd_def
        rob_.io.commitWb.value := load_final
        cdb2.valid := head.rd_def
        cdb2.value := load_final

        rat_.io.commit.en := head.rd_def && head.rfWen
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
