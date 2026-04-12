package ysyx.core.backend

import chisel3._
import chisel3.util._

import ysyx.core.common._

class MDUInput extends NPCBundle {
  val mdu_op = MDUOpType()
  val rob_tag = UInt(robEntryBits.W)
  val prd = UInt(NRPhyRegBits.W)
  val prf_wen = Bool()
}

class MDUOutput extends NPCBundle {
  val result = UInt(dataBits.W)
  val rob_tag = UInt(robEntryBits.W)
  val prd = UInt(NRPhyRegBits.W)
  val prf_wen = Bool()
}

class MDU extends ExecUnit(new MDUInput, new MDUOutput) {
  io.in.ready := io.out.ready
  io.out.valid := io.in.valid

  prf(0).addr := prs1
  prf(1).addr := prs2
  val op1 = prf(0).data
  val op2 = prf(1).data
  val op1w = op1(31, 0)
  val op2w = op2(31, 0)

  import ysyx.core.lsu.SignExt

  io.out.bits.rob_tag := io.in.bits.rob_tag
  io.out.bits.prd := io.in.bits.prd
  io.out.bits.prf_wen := io.in.bits.prf_wen
  io.out.bits.result := 0.U

  // 64-bit signed overflow: (-2^63) / (-1)
  val min_s64 = (1.U << 63).asSInt // -2^63
  val signed_overflow = (op1.asSInt === min_s64) && (op2.asSInt === (-1).S)
  val min_s32 = (1.U << 31)(31, 0)
  val signed_overflow_w = (op1w.asSInt === min_s32.asSInt) && (op2w.asSInt === (-1).S(32.W))

  switch(io.in.bits.mdu_op) {
    is(MDUOpType.mdu_MUL) {
      io.out.bits.result := (op1.asSInt * op2.asSInt).asUInt(63, 0)
    }
    is(MDUOpType.mdu_MULH) {
      io.out.bits.result := (op1.asSInt * op2.asSInt).asUInt(127, 64)
    }
    is(MDUOpType.mdu_MULHSU) {
      io.out.bits.result := (op1.asSInt * op2.zext).asUInt(127, 64)
    }
    is(MDUOpType.mdu_MULHU) {
      io.out.bits.result := (op1 * op2)(127, 64)
    }
    is(MDUOpType.mdu_DIV) {
      io.out.bits.result := Mux(op2 === 0.U, ~0.U(dataBits.W),
        Mux(signed_overflow, op1, (op1.asSInt / op2.asSInt).asUInt))
    }
    is(MDUOpType.mdu_DIVU) {
      io.out.bits.result := Mux(op2 === 0.U, ~0.U(dataBits.W), op1 / op2)
    }
    is(MDUOpType.mdu_REM) {
      io.out.bits.result := Mux(op2 === 0.U, op1,
        Mux(signed_overflow, 0.U, (op1.asSInt % op2.asSInt).asUInt))
    }
    is(MDUOpType.mdu_REMU) {
      io.out.bits.result := Mux(op2 === 0.U, op1, op1 % op2)
    }
    is(MDUOpType.mdu_MULW) {
      io.out.bits.result := SignExt((op1w.asSInt * op2w.asSInt).asUInt(31, 0))
    }
    is(MDUOpType.mdu_DIVW) {
      io.out.bits.result := Mux(op2w === 0.U, ~0.U(dataBits.W),
        Mux(signed_overflow_w, SignExt(op1w),
          SignExt((op1w.asSInt / op2w.asSInt).asUInt(31, 0))))
    }
    is(MDUOpType.mdu_DIVUW) {
      io.out.bits.result := Mux(op2w === 0.U, ~0.U(dataBits.W),
        SignExt((op1w / op2w)(31, 0)))
    }
    is(MDUOpType.mdu_REMW) {
      io.out.bits.result := Mux(op2w === 0.U, SignExt(op1w),
        Mux(signed_overflow_w, 0.U,
          SignExt((op1w.asSInt % op2w.asSInt).asUInt(31, 0))))
    }
    is(MDUOpType.mdu_REMUW) {
      io.out.bits.result := Mux(op2w === 0.U, SignExt(op1w),
        SignExt((op1w % op2w)(31, 0)))
    }
  }
}

class MDUExtra extends NPCBundle {
  val mdu_op = MDUOpType()
  val prd = UInt(NRPhyRegBits.W)
  val prf_wen = Bool()
}

class MDUIssueQueue extends IssueQueue(new MDUExtra, entries = 4)
