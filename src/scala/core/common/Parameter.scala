package ysyx.core.common

import chisel3._
import chisel3.util._

/** @brief
  *   有几个通用寄存器
  */
trait HasRegFileParameter {
  val NRReg     = 32
  val NRRegbits = log2Up(NRReg)
}

trait HasCSRParameter {
  val NRCSR     = 0x1000
  val NRCSRbits = log2Up(NRCSR)

  val MSTATUS   = 0x0300 // 防止被意外符号拓展(scala只有signed int)
  val MTVEC     = 0x0305
  val MEPC      = 0x0341
  val MCAUSE    = 0x0342
  val MTVAL     = 0x0343
  val MCYCLE    = 0x0b00
  val MCYCLEH   = 0x0b80
  val MVENDORID = 0x0f11
  val MARCHID   = 0x0f12
}

/** @brief
  *   有 core 的一些参数
  */
trait HasCoreParameter {
  val XLEN:      Int = 32 // 机器字长
  val InstLen:   Int = 32 // 指令字长
  val OpcodeLen: Int = 7
  val dataBytes     = XLEN >> 3           // 一个 word 有几个字节  4
  val dataBytesBits = log2Ceil(dataBytes) // 一个 word 有几个字节的位宽 2
}
