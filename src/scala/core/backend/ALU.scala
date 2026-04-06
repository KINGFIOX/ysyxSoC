package ysyx.core.backend

import chisel3._
import chisel3.util._

import ysyx.core.common._

object ALUOpType extends ChiselEnum {
  val alu_X, alu_ADD, alu_SUB, alu_AND, alu_OR, alu_XOR, alu_SLL, alu_SRL,
      alu_SRA, alu_SLT, alu_SLTU, alu_ADDW, alu_SUBW, alu_SLLW, alu_SRLW,
      alu_SRAW = Value
}

class ALUInput extends NPCBundle {
  val op1 = UInt(dataBits.W)
  val op2 = UInt(dataBits.W)
  val alu_op = ALUOpType()
  val rob_tag = UInt(robEntryBits.W)
  val prd = UInt(NRPhyRegBits.W)
  val prf_wen = Bool()
}

class ALUOutput extends NPCBundle {
  val result = UInt(dataBits.W)
  val rob_tag = UInt(robEntryBits.W)
  val prd = UInt(NRPhyRegBits.W)
  val prf_wen = Bool()
}

class ALU extends ExecUnit(new ALUInput, new ALUOutput) {
  io.in.ready := io.out.ready
  io.out.valid := io.in.valid

  val op1 = io.in.bits.op1
  val op2 = io.in.bits.op2
  val shamt = op2(log2Up(dataBits) - 1, 0)
  val shamtW = op2(4, 0)
  val op1w = op1(31, 0)

  io.out.bits.result := 0.U
  io.out.bits.rob_tag := io.in.bits.rob_tag
  io.out.bits.prd := io.in.bits.prd
  io.out.bits.prf_wen := io.in.bits.prf_wen

  import ysyx.core.lsu.SignExt

  switch(io.in.bits.alu_op) {
    is(ALUOpType.alu_ADD) { io.out.bits.result := op1 + op2 }
    is(ALUOpType.alu_SUB) { io.out.bits.result := op1 - op2 }
    is(ALUOpType.alu_AND) { io.out.bits.result := op1 & op2 }
    is(ALUOpType.alu_OR) { io.out.bits.result := op1 | op2 }
    is(ALUOpType.alu_XOR) { io.out.bits.result := op1 ^ op2 }
    is(ALUOpType.alu_SLL) { io.out.bits.result := op1 << shamt }
    is(ALUOpType.alu_SRL) { io.out.bits.result := op1 >> shamt }
    is(ALUOpType.alu_SRA) { io.out.bits.result := (op1.asSInt >> shamt).asUInt }
    is(ALUOpType.alu_SLT) { io.out.bits.result := op1.asSInt < op2.asSInt }
    is(ALUOpType.alu_SLTU) { io.out.bits.result := op1 < op2 }
    is(ALUOpType.alu_ADDW) { io.out.bits.result := SignExt((op1w + op2(31, 0))(31, 0)) }
    is(ALUOpType.alu_SUBW) { io.out.bits.result := SignExt((op1w - op2(31, 0))(31, 0)) }
    is(ALUOpType.alu_SLLW) { io.out.bits.result := SignExt((op1w << shamtW)(31, 0)) }
    is(ALUOpType.alu_SRLW) { io.out.bits.result := SignExt((op1w >> shamtW)(31, 0)) }
    is(ALUOpType.alu_SRAW) { io.out.bits.result := SignExt((op1w.asSInt >> shamtW).asUInt(31, 0)) }
  }
}

class ALUExtra extends NPCBundle {
  val alu_op = ALUOpType()
  val prd = UInt(NRPhyRegBits.W)
  val prf_wen = Bool()
  val use_imm = Bool()
  val imm = UInt(dataBits.W)
}

class ALUIssueQueue extends IssueQueue(new ALUExtra, entries = 8)
