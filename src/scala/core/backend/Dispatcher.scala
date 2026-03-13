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

    val disp_alu = Decoupled(new IQEnqData(new ALUExtra))
    val disp_bru = Decoupled(new IQEnqData(new BRUExtra))
    val disp_agu = Decoupled(new IQEnqData(new AGUExtra))

    val rat_write = Flipped(new RATWritePort)
  })

  val d = io.in.bits

  val iq_ready = MuxCase(
    true.B,
    Seq(
      d.go_to_alu -> io.disp_alu.ready,
      d.go_to_bru -> io.disp_bru.ready,
      d.go_to_agu -> io.disp_agu.ready
    )
  )

  io.in.ready := io.rob_enq.ready && iq_ready && !io.flush
  val can_dispatch = io.in.fire

  // --- ROB enqueue ---
  io.rob_enq.valid := can_dispatch

  val rob_enq = io.rob_enq.bits
  rob_enq.wbSel := d.ctrl.wbSel
  rob_enq.mem := d.ctrl.mem
  rob_enq.csrOp := d.ctrl.csrOp
  rob_enq.csrWen := d.ctrl.csrWen
  rob_enq.npcOp := d.ctrl.npcOp
  rob_enq.pc := d.pc
  rob_enq.inst := d.inst
  rob_enq.imm := d.imm
  rob_enq.rd_idx := d.rd_idx
  rob_enq.rd_def := d.rd_def
  rob_enq.except_en := d.dispatch_except_en
  rob_enq.mcause := d.dispatch_mcause
  rob_enq.mtval := d.dispatch_mtval
  rob_enq.dispatch_executed := d.dispatch_resolved
  rob_enq.rd_val := d.disp_rd_val
  rob_enq.rd_val_valid := d.disp_rd_val_valid
  rob_enq.mispredict := d.disp_mispredict
  rob_enq.target_npc := d.disp_target_npc

  val rob_tag = io.rob_tag

  // --- ALU IQ enqueue ---
  io.disp_alu.valid := can_dispatch && d.go_to_alu
  io.disp_alu.bits.src1 := d.src1

  val imm_as_src2 = d.ctrl.aluSel2 === ALUOp2Sel.OP2_IMM
  io.disp_alu.bits.src2.value := Mux(imm_as_src2, d.imm, d.src2.value)
  io.disp_alu.bits.src2.tag := Mux(imm_as_src2, 0.U, d.src2.tag)
  io.disp_alu.bits.src2.ready := Mux(imm_as_src2, true.B, d.src2.ready)

  io.disp_alu.bits.extra.aluOp := d.ctrl.aluOp
  io.disp_alu.bits.extra.rd_def := d.rd_def
  io.disp_alu.bits.rob_tag := rob_tag

  // --- BRU IQ enqueue ---
  io.disp_bru.valid := can_dispatch && d.go_to_bru
  io.disp_bru.bits.src1 := d.src1
  io.disp_bru.bits.src2 := d.src2
  io.disp_bru.bits.extra.bruOp := d.ctrl.bruOp
  io.disp_bru.bits.rob_tag := rob_tag

  // --- AGU IQ enqueue ---
  io.disp_agu.valid := can_dispatch && d.go_to_agu
  io.disp_agu.bits.src1 := d.src1

  val is_load = d.ctrl.mem.r_en
  io.disp_agu.bits.src2.value := Mux(is_load, 0.U, d.src2.value)
  io.disp_agu.bits.src2.tag := Mux(is_load, 0.U, d.src2.tag)
  io.disp_agu.bits.src2.ready := Mux(is_load, true.B, d.src2.ready)

  io.disp_agu.bits.extra.imm := d.imm
  io.disp_agu.bits.rob_tag := rob_tag

  // --- RAT update ---
  io.rat_write.en := can_dispatch && d.rd_def && !d.dispatch_except_en
  io.rat_write.addr := d.rd_idx
  io.rat_write.tag := rob_tag
}
