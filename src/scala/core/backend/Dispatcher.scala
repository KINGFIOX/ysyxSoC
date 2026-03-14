package ysyx.core.backend

import chisel3._
import chisel3.util._

import ysyx.core.common._

class DispFwdBundle extends NPCBundle {
  val rd_wen = Bool()
  val rd_idx = UInt(NRRegbits.W)
  val rd_tag = UInt(robEntryBits.W)
}

class Dispatcher extends NPCModule {
  val io = IO(new Bundle {
    val in = Flipped(Decoupled(new RenameStageOutput))
    val flush = Input(Bool())
    val rename_fwd = new DispFwdBundle
    val rob_enq = Decoupled(new RobEnqData)
    val rob_tag = Input(UInt(robEntryBits.W))
    val disp_alu = Decoupled(new IQEnqData(new ALUExtra))
    val disp_bru = Decoupled(new IQEnqData(new BRUExtra))
    val disp_agu = Decoupled(new IQEnqData(new AGUExtra))
    val rat_write = Flipped(new RATWritePort)
  })

  val in = io.in.bits
  val dec = in.dec
  val ctrl = dec.ctrl

  val except_en = ctrl.has_except || dec.ifu.exceptionEn
  val rd_def = ctrl.rf_wen && (dec.rd_idx =/= 0.U) && !except_en

  val go_to_alu = ctrl.go_to_alu && !except_en
  val go_to_bru = ctrl.go_to_bru && !except_en
  val go_to_agu = ctrl.go_to_agu && !except_en
  val go_to_fu = go_to_alu || go_to_bru || go_to_agu

  // ============================================================
  // Ready / Valid
  // ============================================================
  // format: off
  val iq_ready = MuxCase(true.B, Seq(
    go_to_alu -> io.disp_alu.ready,
    go_to_bru -> io.disp_bru.ready,
    go_to_agu -> io.disp_agu.ready,
  ))
  // format: on

  io.in.ready := io.rob_enq.ready && iq_ready && !io.flush
  io.rob_enq.valid := io.in.valid && iq_ready && !io.flush
  io.disp_alu.valid := io.in.valid && io.rob_enq.ready && go_to_alu && !io.flush
  io.disp_bru.valid := io.in.valid && io.rob_enq.ready && go_to_bru && !io.flush
  io.disp_agu.valid := io.in.valid && io.rob_enq.ready && go_to_agu && !io.flush

  // ============================================================
  // ROB Enqueue
  // ============================================================
  val d = io.rob_enq.bits
  d.mem := ctrl.mem
  d.csr_op := ctrl.csr_op
  d.csr_wen := ctrl.csr_wen
  d.pc := dec.ifu.pc
  d.inst := dec.ifu.inst
  d.imm := dec.imm
  d.rd_idx := dec.rd_idx
  d.rd_def := rd_def
  d.except_en := except_en
  d.mcause := in.dispatch_mcause
  d.mtval := in.dispatch_mtval
  d.is_jalr := ctrl.is_jalr
  d.is_mret := ctrl.is_mret
  d.dispatch_executed := !go_to_fu
  d.rd_val := in.disp_rd_val
  d.rd_val_valid := in.disp_rd_val_valid
  d.mispredict := in.disp_mispredict
  d.target_npc := in.disp_target_npc

  // ============================================================
  // ALU Issue Queue
  // ============================================================
  io.disp_alu.bits.src1 := in.src1
  val alu_imm_src = Wire(new IQSrcBundle)
  alu_imm_src.value := dec.imm
  alu_imm_src.tag := 0.U
  alu_imm_src.ready := true.B
  io.disp_alu.bits.src2 := Mux(
    ctrl.alu_sel2 === ALUSel2.OP2_IMM,
    alu_imm_src,
    in.src2
  )
  io.disp_alu.bits.extra.aluOp := ctrl.alu_op
  io.disp_alu.bits.extra.rd_def := rd_def
  io.disp_alu.bits.rob_tag := io.rob_tag

  // ============================================================
  // BRU Issue Queue
  // ============================================================
  io.disp_bru.bits.src1 := in.src1
  io.disp_bru.bits.src2 := in.src2
  io.disp_bru.bits.extra.bru_op := ctrl.bru_op
  io.disp_bru.bits.rob_tag := io.rob_tag

  // ============================================================
  // AGU Issue Queue
  // ============================================================
  io.disp_agu.bits.src1 := in.src1
  io.disp_agu.bits.src2 := in.src2
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
  // Disabled: no pipeline regs between Rename and Dispatch yet,
  // enabling this would cause a self-dependency on the same
  // instruction. Enable when pipeline registers are inserted.
  // ============================================================
  io.rename_fwd.rd_wen := false.B
  io.rename_fwd.rd_idx := dec.rd_idx
  io.rename_fwd.rd_tag := io.rob_tag
}
