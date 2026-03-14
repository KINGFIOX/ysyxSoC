package ysyx.core.backend

import chisel3._
import chisel3.util._

import ysyx.core.common._

object ALUOpType extends ChiselEnum {
  val alu_X, alu_ADD, alu_SUB, alu_AND, alu_OR, alu_XOR, alu_SLL, alu_SRL,
      alu_SRA, alu_SLT, alu_SLTU = Value
}

class ALUInput extends NPCBundle {
  val op1 = UInt(dataBits.W)
  val op2 = UInt(dataBits.W)
  val alu_op = ALUOpType()
  val rob_tag = UInt(robEntryBits.W)
  val rd_def = Bool()
}

class ALUOutput extends NPCBundle {
  val result = UInt(dataBits.W)
  val rob_tag = UInt(robEntryBits.W)
  val rd_def = Bool()
}

class ALU extends ExecUnit(new ALUInput, new ALUOutput) {
  io.in.ready := io.out.ready
  io.out.valid := io.in.valid

  val op1 = io.in.bits.op1
  val op2 = io.in.bits.op2
  val shamt = op2(log2Up(dataBits) - 1, 0)

  io.out.bits.result := 0.U
  io.out.bits.rob_tag := io.in.bits.rob_tag
  io.out.bits.rd_def := io.in.bits.rd_def

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
  }
}

class ALUExtra extends NPCBundle {
  val alu_op = ALUOpType()
  val rd_def = Bool()
}

class ALUIssueQueue extends IssueQueue(new ALUExtra, entries = 8, numOps = 2)
