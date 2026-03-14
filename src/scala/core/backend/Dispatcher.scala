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
  val iq_ready = MuxCase(
    true.B, // dispatch_resolved: jal,lui,auipc,mret, exception
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
  enq.mem := ctrl.mem
  enq.csr_op := ctrl.csr_op
  enq.csr_wen := ctrl.csr_wen
  enq.pc := dec.ifu.pc
  enq.inst := dec.ifu.inst
  enq.imm := dec.imm // for csr only
  enq.rd_idx := dec.rd_idx
  enq.rd_def := rd_def
  enq.except_en := except_en
  enq.mcause := in.dispatch_mcause
  enq.mtval := in.dispatch_mtval
  enq.is_jalr := ctrl.is_jalr
  enq.is_mret := ctrl.is_mret
  enq.dispatch_executed := !go_to_fu
  enq.rd_val := in.disp_rd_val
  enq.rd_val_valid := in.disp_rd_val_valid
  enq.mispredict := in.disp_mispredict
  enq.target_npc := in.disp_target_npc

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
  io.rename_fwd.rd_wen := false.B // TODO:
  io.rename_fwd.rd_idx := dec.rd_idx
  io.rename_fwd.rd_tag := io.rob_tag
}
