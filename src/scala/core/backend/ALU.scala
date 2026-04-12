package ysyx.core.backend

import chisel3._
import chisel3.util._

import ysyx.core.common._

object ALUOpType extends ChiselEnum {
  val alu_X, alu_ADD, alu_SUB, alu_AND, alu_OR, alu_XOR, alu_SLL, alu_SRL,
      alu_SRA, alu_SLT, alu_SLTU, alu_ADDW, alu_SUBW, alu_SLLW, alu_SRLW,
      alu_SRAW,
      alu_MUL, alu_MULH, alu_MULHSU, alu_MULHU, alu_DIV, alu_DIVU,
      alu_REM, alu_REMU, alu_MULW, alu_DIVW, alu_DIVUW, alu_REMW,
      alu_REMUW = Value
}

class ALUInput extends NPCBundle {
  val alu_op = ALUOpType()
  val rob_tag = UInt(robEntryBits.W)
  val prd = UInt(NRPhyRegBits.W)
  val prf_wen = Bool()
  val use_imm = Bool()
  val imm = UInt(dataBits.W)
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

  prf(0).addr := prs1
  val op1 = prf(0).data
  prf(1).addr := prs2
  val op2 = Mux(io.in.bits.use_imm, io.in.bits.imm, prf(1).data)
  val shamt = op2(log2Up(dataBits) - 1, 0)
  val shamtW = op2(4, 0)
  val op1w = op1(31, 0)

  io.out.bits.result := 0.U
  io.out.bits.rob_tag := io.in.bits.rob_tag
  io.out.bits.prd := io.in.bits.prd
  io.out.bits.prf_wen := io.in.bits.prf_wen

  import ysyx.core.lsu.SignExt

  // M-extension helpers
  val mul_result = (op1.asSInt * op2.asSInt).asUInt
  val op2w = op2(31, 0)

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
    // RV64M
    is(ALUOpType.alu_MUL)    { io.out.bits.result := mul_result(63, 0) }
    is(ALUOpType.alu_MULH)   { io.out.bits.result := mul_result(127, 64) }
    is(ALUOpType.alu_MULHSU) { io.out.bits.result := (op1.asSInt * op2.zext).asUInt(127, 64) }
    is(ALUOpType.alu_MULHU)  { io.out.bits.result := (op1 * op2)(127, 64) }
    is(ALUOpType.alu_DIV) {
      io.out.bits.result := Mux(op2 === 0.U, ~0.U(dataBits.W),
        (op1.asSInt / op2.asSInt).asUInt)
    }
    is(ALUOpType.alu_DIVU) {
      io.out.bits.result := Mux(op2 === 0.U, ~0.U(dataBits.W), op1 / op2)
    }
    is(ALUOpType.alu_REM) {
      io.out.bits.result := Mux(op2 === 0.U, op1,
        (op1.asSInt % op2.asSInt).asUInt)
    }
    is(ALUOpType.alu_REMU) {
      io.out.bits.result := Mux(op2 === 0.U, op1, op1 % op2)
    }
    // RV64M *W
    is(ALUOpType.alu_MULW) {
      io.out.bits.result := SignExt((op1w.asSInt * op2w.asSInt).asUInt(31, 0))
    }
    is(ALUOpType.alu_DIVW) {
      io.out.bits.result := Mux(op2w === 0.U, ~0.U(dataBits.W),
        SignExt((op1w.asSInt / op2w.asSInt).asUInt(31, 0)))
    }
    is(ALUOpType.alu_DIVUW) {
      io.out.bits.result := Mux(op2w === 0.U, ~0.U(dataBits.W),
        SignExt((op1w / op2w)(31, 0)))
    }
    is(ALUOpType.alu_REMW) {
      io.out.bits.result := Mux(op2w === 0.U, SignExt(op1w),
        SignExt((op1w.asSInt % op2w.asSInt).asUInt(31, 0)))
    }
    is(ALUOpType.alu_REMUW) {
      io.out.bits.result := Mux(op2w === 0.U, SignExt(op1w),
        SignExt((op1w % op2w)(31, 0)))
    }
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
