package ysyx.core.component

import chisel3._
import chisel3.util._
import ysyx.core.common.HasCoreParameter

object BRUOpType extends ChiselEnum {
  val bru_X, bru_BLT, bru_BLTU, bru_BGE, bru_BGEU, bru_BEQ, bru_BNE = Value
}

class BRUInBundle extends Bundle with HasCoreParameter {
  val rs1_v  = UInt(XLEN.W)
  val rs2_v  = UInt(XLEN.W)
  val op = BRUOpType()
}

class BRUOutputBundle extends Bundle with HasCoreParameter {
  val br_flag = Bool()
}

/** @brief
  *   仅用来判断是否要跳转
  */
class BRU extends Module with HasCoreParameter {
  val io = IO(new Bundle {
    val in  = Flipped(new BRUInBundle)
    val out = new BRUOutputBundle
  })

  io.out.br_flag := false.B // default

  switch(io.in.op) {
    is(BRUOpType.bru_BLT) { when(io.in.rs1_v.asSInt < io.in.rs2_v.asSInt) { io.out.br_flag := true.B } }
    is(BRUOpType.bru_BLTU) { when(io.in.rs1_v < io.in.rs2_v) { io.out.br_flag := true.B } }
    is(BRUOpType.bru_BGE) { when(io.in.rs1_v.asSInt >= io.in.rs2_v.asSInt) { io.out.br_flag := true.B } }
    is(BRUOpType.bru_BGEU) { when(io.in.rs1_v >= io.in.rs2_v) { io.out.br_flag := true.B } }
    is(BRUOpType.bru_BEQ) { when(io.in.rs1_v === io.in.rs2_v) { io.out.br_flag := true.B } }
    is(BRUOpType.bru_BNE) { when(io.in.rs1_v =/= io.in.rs2_v) { io.out.br_flag := true.B } }
    // BRUOpType.bru_X
  }
}
