package ysyx.core.backend

import chisel3._
import chisel3.util._

import ysyx.core.common._

object BRUOpType extends ChiselEnum {
  val bru_X, bru_BLT, bru_BLTU, bru_BGE, bru_BGEU, bru_BEQ, bru_BNE = Value
}

class BRUInput extends NPCBundle {
  val rs1_v = UInt(dataBits.W)
  val rs2_v = UInt(dataBits.W)
  val op = BRUOpType()
}

class BRUOutput extends NPCBundle {
  val br_flag = Bool()
}

class BRU extends ExecUnit(new BRUInput, new BRUOutput) {
  io.in.ready := io.out.ready
  io.out.valid := io.in.valid
  io.out.bits.br_flag := false.B

  // format: off
  switch(io.in.bits.op) {
    is(BRUOpType.bru_BLT) { when(io.in.bits.rs1_v.asSInt < io.in.bits.rs2_v.asSInt) { io.out.bits.br_flag := true.B } }
    is(BRUOpType.bru_BLTU) { when(io.in.bits.rs1_v < io.in.bits.rs2_v) { io.out.bits.br_flag := true.B } }
    is(BRUOpType.bru_BGE) { when(io.in.bits.rs1_v.asSInt >= io.in.bits.rs2_v.asSInt) { io.out.bits.br_flag := true.B } }
    is(BRUOpType.bru_BGEU) { when(io.in.bits.rs1_v >= io.in.bits.rs2_v) { io.out.bits.br_flag := true.B } }
    is(BRUOpType.bru_BEQ) { when(io.in.bits.rs1_v === io.in.bits.rs2_v) { io.out.bits.br_flag := true.B } }
    is(BRUOpType.bru_BNE) { when(io.in.bits.rs1_v =/= io.in.bits.rs2_v) { io.out.bits.br_flag := true.B } }
  }
  // format: on
}

class BRUExtra extends NPCBundle {
  val bru_op = BRUOpType()
}

class BRUIssueQueue extends IssueQueue(new BRUExtra, entries = 4)
