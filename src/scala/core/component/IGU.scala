/** @brief
  *   ExtU - 立即数扩展单元
  */

package ysyx.core.component

import chisel3._
import chisel3.util._
import ysyx.core.common.HasCoreParameter

/** 立即数类型 */
object ImmType extends ChiselEnum {
  val IMM_I, IMM_S, IMM_B, IMM_U, IMM_J = Value
}

class IGUInputBundle extends Bundle with HasCoreParameter {
  val inst_31_7 = UInt((InstLen - OpcodeLen).W) // inst[31:7], 不需要 opcode 部分
  val immType   = ImmType()
}

class IGUOutputBundle extends Bundle with HasCoreParameter {
  val imm = UInt(XLEN.W)
}

/** @brief
  *   Immediate Generator Unit, 立即数生成器
  */
class IGU extends Module with HasCoreParameter {
  val io = IO(new Bundle {
    val in  = Flipped(new IGUInputBundle)
    val out = new IGUOutputBundle
  })

  // inst 输入是 inst[31:7], 共25位, 索引映射: inst[i] = 原inst[i+7]
  private val inst = io.in.inst_31_7

  // I-type: imm[11:0] = 原inst[31:20] = inst[24:13]
  private val immI = Cat(Fill(20, inst(24)) /*符号拓展*/, inst(24, 13))

  // S-type: imm[11:0] = {原inst[31:25], 原inst[11:7]} = {inst[24:18], inst[4:0]}
  private val immS = Cat(Fill(20, inst(24)), inst(24, 18), inst(4, 0))

  // B-type: imm[12:1] = {原inst[31], 原inst[7], 原inst[30:25], 原inst[11:8]} = {inst[24], inst[0], inst[23:18], inst[4:1]}
  private val immB = Cat(Fill(19, inst(24)), inst(24), inst(0), inst(23, 18), inst(4, 1), 0.U(1.W))

  // U-type: imm[31:12] = 原inst[31:12] = inst[24:5], imm[11:0] = 0
  private val immU = Cat(inst(24, 5), 0.U(12.W))

  // J-type: imm[20:1] = {原inst[31], 原inst[19:12], 原inst[20], 原inst[30:21]} = {inst[24], inst[12:5], inst[13], inst[23:14]}
  private val immJ = Cat(Fill(11, inst(24)), inst(24), inst(12, 5), inst(13), inst(23, 14), 0.U(1.W))

  io.out.imm := MuxCase(
    0.U,
    Seq(
      (io.in.immType === ImmType.IMM_I) -> immI,
      (io.in.immType === ImmType.IMM_S) -> immS,
      (io.in.immType === ImmType.IMM_B) -> immB,
      (io.in.immType === ImmType.IMM_U) -> immU,
      (io.in.immType === ImmType.IMM_J) -> immJ
    )
  )
}
