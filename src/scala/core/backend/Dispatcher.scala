package ysyx.core.backend

import chisel3._
import chisel3.util._

import ysyx.core.common._

class Dispatcher extends NPCModule {
  val io = IO(new Bundle {
    val in = Flipped(Decoupled(new RenameStageOutput))
    val flush = Input(Bool())
    val rob_enq = Decoupled(new RobEnqData)
    val rob_tag = Input(UInt(robEntryBits.W))
    val alu_iq = Decoupled(new IQEnqData(new ALUExtra))
    val bru_iq = Decoupled(new IQEnqData(new BRUExtra))
    val agu_iq = Decoupled(new IQEnqData(new AGUExtra))
    // dispatch-resolved PRF write
    val prf_write = Valid(new PRFWritePort)
    // dispatch-resolved wakeup
    val wakeup = Valid(new WakeupPort)
  })

  val in = io.in.bits
  val dec = in.dec
  val ctrl = dec.ctrl
  val inst_type = ctrl.inst_type

  // ============================================================
  // Routing derived from inst_type
  // ============================================================
  val has_except = dec.has_except

  // format: off
  val go_to_alu = Seq(InstType.R_ALU, InstType.I_ALU, InstType.R_ALU_W, InstType.I_ALU_W, InstType.JALR, InstType.CSR).map(inst_type === _).reduce(_ || _) && !has_except
  val go_to_bru = (inst_type === InstType.BRANCH) && !has_except
  val go_to_agu = Seq(InstType.LOAD, InstType.STORE).map(inst_type === _).reduce(_ || _) && !has_except
  // format: on

  val rd_wen = in.rd_wen

  // ============================================================
  // Ready / Valid
  // ============================================================
  val iq_ready = MuxCase(
    true.B,
    Seq(
      go_to_alu -> io.alu_iq.ready,
      go_to_bru -> io.bru_iq.ready,
      go_to_agu -> io.agu_iq.ready
    )
  )

  io.in.ready := io.rob_enq.ready && iq_ready && !io.flush
  io.rob_enq.valid := io.in.valid && iq_ready && !io.flush
  io.alu_iq.valid := io.in.valid && io.rob_enq.ready && go_to_alu && !io.flush
  io.bru_iq.valid := io.in.valid && io.rob_enq.ready && go_to_bru && !io.flush
  io.agu_iq.valid := io.in.valid && io.rob_enq.ready && go_to_agu && !io.flush

  // ============================================================
  // ROB Enqueue
  // ============================================================
  val enq = io.rob_enq.bits
  enq.pc := dec.pc
  enq.inst := dec.inst_bits
  enq.inst_type := inst_type
  enq.mem := ctrl.mem
  enq.csr.addr := dec.imm(NRCSRbits - 1, 0)
  enq.csr.op := ctrl.csr_op
  enq.csr.wdata := 0.U
  enq.arch_rd := Mux(rd_wen, dec.rd_idx, 0.U)
  enq.new_prd := in.prd
  enq.old_prd := in.old_prd
  enq.rd_wen := rd_wen
  enq.except.valid := has_except
  enq.except.mcause := dec.mcause
  enq.except.mtval := dec.mtval
  enq.mret.mepc := 0.U
  enq.bru.snpc := dec.pc + 4.U
  enq.bru.dnpc := in.disp_target_npc
  enq.bru.br_flag := false.B
  enq.jal.dnpc := in.disp_target_npc
  enq.jal.is_call := dec.is_call
  enq.jalr.dnpc := 0.U
  enq.jalr.dnpc_rdy := false.B
  enq.jalr.is_ret := dec.is_ret
  enq.predict_npc := dec.predict_npc
  enq.ghr := dec.ghr

  // ============================================================
  // ALU Issue Queue
  // ============================================================
  // format: off
  val alu_prf_wen = Seq(InstType.R_ALU, InstType.I_ALU, InstType.R_ALU_W, InstType.I_ALU_W).map(inst_type === _).reduce(_ || _) && rd_wen
  // format: on
  io.alu_iq.bits.prs1.preg := in.prs1
  io.alu_iq.bits.prs1.ready := in.prs1_ready
  io.alu_iq.bits.prs2.preg := in.prs2
  io.alu_iq.bits.prs2.ready := in.prs2_ready
  io.alu_iq.bits.imm := dec.imm
  io.alu_iq.bits.extra.alu_op := ctrl.alu_op
  io.alu_iq.bits.extra.prd := in.prd
  io.alu_iq.bits.extra.prf_wen := alu_prf_wen
  // format: off
  io.alu_iq.bits.extra.use_imm := Seq(InstType.I_ALU, InstType.I_ALU_W, InstType.JALR).map(inst_type === _).reduce(_ || _)
  // format: on
  io.alu_iq.bits.rob_tag := io.rob_tag

  // ============================================================
  // BRU Issue Queue
  // ============================================================
  io.bru_iq.bits.prs1.preg := in.prs1
  io.bru_iq.bits.prs1.ready := in.prs1_ready
  io.bru_iq.bits.prs2.preg := in.prs2
  io.bru_iq.bits.prs2.ready := in.prs2_ready
  io.bru_iq.bits.imm := dec.imm
  io.bru_iq.bits.extra.bru_op := ctrl.bru_op
  io.bru_iq.bits.rob_tag := io.rob_tag

  // ============================================================
  // AGU Issue Queue
  // ============================================================
  io.agu_iq.bits.prs1.preg := in.prs1
  io.agu_iq.bits.prs1.ready := in.prs1_ready
  io.agu_iq.bits.prs2.preg := in.prs2
  io.agu_iq.bits.prs2.ready := in.prs2_ready
  io.agu_iq.bits.imm := dec.imm
  io.agu_iq.bits.extra.offset := dec.imm
  io.agu_iq.bits.rob_tag := io.rob_tag

  // ============================================================
  // Dispatch-resolved PRF write (JAL, JALR, LUI, AUIPC)
  // ============================================================
  io.prf_write.valid := io.in.fire && in.disp_rd_defen
  io.prf_write.bits.addr := in.prd
  io.prf_write.bits.data := in.disp_rd_val

  // ============================================================
  // Dispatch-resolved wakeup
  // ============================================================
  io.wakeup.valid := io.in.fire && in.disp_rd_defen
  io.wakeup.bits.prd := in.prd
}
