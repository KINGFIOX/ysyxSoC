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
    val disp_alu = Decoupled(new IQEnqData(new ALUExtra, 2))
    val disp_bru = Decoupled(new IQEnqData(new BRUExtra, 2))
    val disp_agu = Decoupled(new IQEnqData(new AGUExtra, 2))
    val rat_write = Flipped(new RATWritePort)
  })

  val in = io.in.bits
  val dec = in.dec
  val ctrl = dec.ctrl
  val inst_type = ctrl.inst_type

  // ============================================================
  // Routing derived from inst_type
  // ============================================================
  val except_en = dec.has_except

  // format: off
  val go_to_alu = Seq(InstType.R_ALU, InstType.I_ALU, InstType.JALR, InstType.CSR)
    .map(inst_type === _).reduce(_ || _) && !except_en
  val go_to_bru = (inst_type === InstType.BRANCH) && !except_en
  val go_to_agu = Seq(InstType.LOAD, InstType.STORE)
    .map(inst_type === _).reduce(_ || _) && !except_en
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
      go_to_alu -> io.disp_alu.ready,
      go_to_bru -> io.disp_bru.ready,
      go_to_agu -> io.disp_agu.ready
    )
  )

  io.in.ready := io.rob_enq.ready && iq_ready && !io.flush
  io.rob_enq.valid := io.in.valid && iq_ready && !io.flush
  io.disp_alu.valid := io.in.valid && io.rob_enq.ready && go_to_alu && !io.flush
  io.disp_bru.valid := io.in.valid && io.rob_enq.ready && go_to_bru && !io.flush
  io.disp_agu.valid := io.in.valid && io.rob_enq.ready && go_to_agu && !io.flush

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
  enq.except.valid := except_en
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
  enq.completed := !go_to_fu

  // ============================================================
  // ALU Issue Queue
  // ============================================================
  io.disp_alu.bits.src(0) := in.src(0)
  val alu_imm_src = Wire(new IQSrcBundle)
  alu_imm_src.value := Mux(is_csr, 0.U, dec.imm)
  alu_imm_src.tag := 0.U
  alu_imm_src.ready := true.B
  io.disp_alu.bits.src(1) := Mux(use_imm || is_csr, alu_imm_src, in.src(1))
  io.disp_alu.bits.extra.alu_op := Mux(is_csr, ALUOpType.alu_ADD, ctrl.alu_op)
  io.disp_alu.bits.extra.rd_def := rd_def
  io.disp_alu.bits.rob_tag := io.rob_tag

  // ============================================================
  // BRU Issue Queue
  // ============================================================
  io.disp_bru.bits.src(0) := in.src(0)
  io.disp_bru.bits.src(1) := in.src(1)
  io.disp_bru.bits.extra.bru_op := ctrl.bru_op
  io.disp_bru.bits.rob_tag := io.rob_tag

  // ============================================================
  // AGU Issue Queue
  // ============================================================
  io.disp_agu.bits.src(0) := in.src(0)
  io.disp_agu.bits.src(1) := in.src(1)
  io.disp_agu.bits.extra.imm := dec.imm
  io.disp_agu.bits.rob_tag := io.rob_tag

  // ============================================================
  // RAT Write
  // ============================================================
  io.rat_write.en := io.in.fire && rd_def
  io.rat_write.addr := dec.rd_idx
  io.rat_write.tag := io.rob_tag

  // ============================================================
  // Rename Forward
  // ============================================================
  io.rename_fwd.rd_def_tag := false.B // TODO: enable when pipeline regs inserted
  io.rename_fwd.rd_idx := Mux(rd_def, dec.rd_idx, 0.U)
  io.rename_fwd.tag := io.rob_tag
  io.rename_fwd.rd_def_val := false.B
  io.rename_fwd.value := 0.U
}
