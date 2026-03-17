package ysyx.core.backend

import chisel3._
import chisel3.util._

import ysyx.core.common._

class DispFwdBundle extends NPCBundle {
  val rd_idx = UInt(NRRegbits.W)
  val rd_def_tag = Bool()
  val tag = UInt(robEntryBits.W)
  val rd_def_val = Bool()
  val value = UInt(dataBits.W)
}

class Dispatcher extends NPCModule {
  val io = IO(new Bundle {
    val in = Flipped(Decoupled(new RenameStageOutput))
    val flush = Input(Bool())
    val rename_fwd = new DispFwdBundle
    val rob_enq = Decoupled(new RobEnqData)
    val rob_tag = Input(UInt(robEntryBits.W))
    val alu_iq = Decoupled(new IQEnqData(new ALUExtra, 2))
    val bru_iq = Decoupled(new IQEnqData(new BRUExtra, 2))
    val agu_iq = Decoupled(new IQEnqData(new AGUExtra, 2))
    val rat = new RATWritePort
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
  val go_to_alu = Seq(InstType.R_ALU, InstType.I_ALU, InstType.JALR, InstType.CSR)
    .map(inst_type === _).reduce(_ || _) && !has_except
  val go_to_bru = (inst_type === InstType.BRANCH) && !has_except
  val go_to_agu = Seq(InstType.LOAD, InstType.STORE)
    .map(inst_type === _).reduce(_ || _) && !has_except
  val go_to_fu = go_to_alu || go_to_bru || go_to_agu

  val inst_writes_rd = Seq(
    InstType.R_ALU, InstType.I_ALU, InstType.JALR, InstType.LOAD,
    InstType.JAL, InstType.LUI, InstType.AUIPC, InstType.CSR
  ).map(inst_type === _).reduce(_ || _)
  // format: on

  val rd_def = inst_writes_rd && (dec.rd_idx =/= 0.U)
  val use_imm = Seq(InstType.I_ALU, InstType.JALR).map(inst_type === _).reduce(_ || _)
  val is_csr = inst_type === InstType.CSR

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
  enq.rd.idx := Mux(rd_def, dec.rd_idx, 0.U)
  enq.rd.value := in.disp_rd_val
  enq.rd.valid := in.disp_rd_val_valid
  enq.except.valid := has_except
  enq.except.mcause := dec.mcause
  enq.except.mtval := dec.mtval
  enq.is_call := dec.is_call
  enq.is_ret := dec.is_ret
  enq.target_npc := MuxCase(
    dec.pc + 4.U,
    Seq(
      (inst_type === InstType.JAL) -> in.disp_target_npc,
      (inst_type === InstType.BRANCH) -> in.disp_target_npc
    )
  )
  enq.predict_npc := dec.predict_npc
  enq.state := Mux(!go_to_fu, RobState.complete, RobState.inflight)

  // ============================================================
  // ALU Issue Queue
  // ============================================================
  // for the reason that rob is unable to wait for rs1, rs2
  // so the instruction waitting for sources should be dispatched to the ALU's issuse queue
  io.alu_iq.bits.src(0) := in.src(0)
  val alu_imm_src = Wire(new IQSrcBundle)
  alu_imm_src.value := Mux(is_csr, 0.U, dec.imm)
  alu_imm_src.tag := 0.U
  alu_imm_src.ready := true.B
  io.alu_iq.bits.src(1) := Mux(use_imm || is_csr, alu_imm_src, in.src(1))
  io.alu_iq.bits.extra.alu_op := Mux(is_csr, ALUOpType.alu_ADD, ctrl.alu_op)
  io.alu_iq.bits.extra.rd_def := rd_def
  io.alu_iq.bits.rob_tag := io.rob_tag

  // ============================================================
  // BRU Issue Queue
  // ============================================================
  io.bru_iq.bits.src(0) := in.src(0)
  io.bru_iq.bits.src(1) := in.src(1)
  io.bru_iq.bits.extra.bru_op := ctrl.bru_op
  io.bru_iq.bits.rob_tag := io.rob_tag

  // ============================================================
  // AGU Issue Queue
  // ============================================================
  io.agu_iq.bits.src(0) := in.src(0)
  io.agu_iq.bits.src(1) := in.src(1)
  io.agu_iq.bits.extra.imm := dec.imm
  io.agu_iq.bits.rob_tag := io.rob_tag

  // ============================================================
  // RAT Write
  // ============================================================
  io.rat.en := io.in.fire && rd_def
  io.rat.addr := dec.rd_idx
  io.rat.tag := io.rob_tag

  // ============================================================
  // Rename Forward
  // ============================================================
  io.rename_fwd.rd_def_tag := io.in.valid && rd_def
  io.rename_fwd.rd_idx := Mux(rd_def, dec.rd_idx, 0.U)
  io.rename_fwd.tag := io.rob_tag
  io.rename_fwd.rd_def_val := io.in.valid && in.disp_rd_val_valid
  io.rename_fwd.value := in.disp_rd_val
}
