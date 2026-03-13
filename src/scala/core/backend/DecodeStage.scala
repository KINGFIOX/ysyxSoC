package ysyx.core.backend

import chisel3._
import chisel3.util._

import ysyx.core.common._
import ysyx.core.frontend._

class DecodeStageOutput extends NPCBundle {
  val pc = UInt(addrBits.W)
  val inst = UInt(InstBits.W)
  val rs1_idx = UInt(NRRegbits.W)
  val rs2_idx = UInt(NRRegbits.W)
  val rd_idx = UInt(NRRegbits.W)
  val imm = UInt(dataBits.W)
  val ctrl = new CUOutput

  val ifu_exceptionEn = Bool()
  val ifu_exception = IFUExceptionType()
  val ifu_mtval = UInt(dataBits.W)

  val is_jal = Bool()
  val is_mret = Bool()
  val is_lui = Bool()
  val is_auipc = Bool()
  val is_branch = Bool()
  val is_mem = Bool()
  val dispatch_resolved = Bool()
  val dispatch_except_en = Bool()
  val skip_iq = Bool()
  val go_to_alu = Bool()
  val go_to_bru = Bool()
  val go_to_agu = Bool()
  val rd_def = Bool()
  val needs_rs1 = Bool()
  val needs_rs2 = Bool()
}

class DecodeStage extends NPCModule {
  val io = IO(new Bundle {
    val in = Flipped(Decoupled(new IFUOutput))
    val out = Decoupled(new DecodeStageOutput)
  })

  io.in.ready := io.out.ready
  io.out.valid := io.in.valid

  val cu_ = Module(new CU)
  val igu_ = Module(new IGU)

  val ifu_out = io.in.bits

  cu_.io.in.inst := ifu_out.inst
  igu_.io.in.inst_31_7 := ifu_out.inst(31, 7)
  igu_.io.in.immType := cu_.io.out.immType

  val o = io.out.bits

  o.pc := ifu_out.pc
  o.inst := ifu_out.inst
  o.rs1_idx := ifu_out.inst(19, 15)
  o.rs2_idx := ifu_out.inst(24, 20)
  o.rd_idx := ifu_out.inst(11, 7)
  o.imm := igu_.io.out.imm
  o.ctrl := cu_.io.out

  o.ifu_exceptionEn := ifu_out.exceptionEn
  o.ifu_exception := ifu_out.exception
  o.ifu_mtval := ifu_out.mtval

  val npcOp = cu_.io.out.npcOp
  val aluSel1 = cu_.io.out.aluSel1
  val aluSel2 = cu_.io.out.aluSel2

  o.is_jal := npcOp === NPCOpType.NPC_JAL
  o.is_mret := npcOp === NPCOpType.NPC_MRET
  o.is_lui := aluSel1 === ALUOp1Sel.OP1_ZERO
  o.is_auipc := aluSel1 === ALUOp1Sel.OP1_PC && npcOp === NPCOpType.NPC_4
  o.is_branch := npcOp === NPCOpType.NPC_BR
  o.is_mem := cu_.io.out.mem.r_en || cu_.io.out.mem.w_en

  o.dispatch_except_en := ifu_out.exceptionEn || cu_.io.out.exceptionEn
  o.dispatch_resolved := o.is_jal || o.is_mret || o.is_lui || o.is_auipc
  o.skip_iq := o.dispatch_except_en || o.dispatch_resolved
  o.go_to_alu := !o.skip_iq && !o.is_branch && !o.is_mem
  o.go_to_bru := o.is_branch && !o.skip_iq
  o.go_to_agu := o.is_mem && !o.skip_iq
  o.rd_def := cu_.io.out.rfWen && (o.rd_idx =/= 0.U)

  o.needs_rs1 := (aluSel1 === ALUOp1Sel.OP1_RS1) || o.is_branch
  o.needs_rs2 := (aluSel2 === ALUOp2Sel.OP2_RS2) || o.is_branch || cu_.io.out.mem.w_en
}
