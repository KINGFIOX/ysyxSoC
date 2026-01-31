/** @brief
  *   ALU - 算术逻辑单元
  */

package ysyx.core.component

import chisel3._
import chisel3.util._

import ysyx.core.common.HasCoreParameter

object ALUOpType extends ChiselEnum {
  val alu_X, alu_ADD, alu_SUB, alu_AND, alu_OR, alu_XOR, alu_SLL, alu_SRL, alu_SRA, alu_SLT, alu_SLTU = Value
}

class ALUInputBundle extends Bundle with HasCoreParameter {
  val op1   = UInt(XLEN.W)
  val op2   = UInt(XLEN.W)
  val aluOp = ALUOpType()
}

class ALUOutputBundle extends Bundle with HasCoreParameter {
  val result = UInt(XLEN.W)
}

class ALU extends Module with HasCoreParameter {
  val io = IO(new Bundle {
    val in  = Flipped(new ALUInputBundle)
    val out = new ALUOutputBundle
  })

  private val op1   = io.in.op1
  private val op2   = io.in.op2
  private val shamt = op2(log2Up(XLEN) - 1, 0) // 移位量 (低 5 位)

  io.out.result := 0.U // default

  switch(io.in.aluOp) {
    is(ALUOpType.alu_ADD) { io.out.result := op1 + op2 }
    is(ALUOpType.alu_SUB) { io.out.result := op1 - op2 }
    is(ALUOpType.alu_AND) { io.out.result := op1 & op2 }
    is(ALUOpType.alu_OR) { io.out.result := op1 | op2 }
    is(ALUOpType.alu_XOR) { io.out.result := op1 ^ op2 }
    is(ALUOpType.alu_SLL) { io.out.result := op1 << shamt }
    is(ALUOpType.alu_SRL) { io.out.result := op1 >> shamt }
    is(ALUOpType.alu_SRA) { io.out.result := (op1.asSInt >> shamt).asUInt }
    is(ALUOpType.alu_SLT) { io.out.result := op1.asSInt < op2.asSInt }
    is(ALUOpType.alu_SLTU) { io.out.result := op1 < op2 }
    // alu_X
  }
}
