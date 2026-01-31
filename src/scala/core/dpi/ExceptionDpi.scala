package ysyx.core.dpi

import chisel3._
import chisel3.util.Cat
import chisel3.util.circt.dpi._

/** 异常处理 DPI 函数
  *
  * 对应 C 函数签名:
  * extern "C" void exception_dpi(int en, int pc, int mcause, int a0, int tval);
  *
  * 注意: 原来的 ExceptionDpiWrapper.sv 使用 always @(*) (组合逻辑)，
  * 但 Chisel DPI API 只支持 clocked 版本，所以改为时钟边沿触发。
  */
object ExceptionDpi extends DPIClockedVoidFunctionImport {
  override val functionName = "exception_dpi"
  override val inputNames = Some(Seq("en", "pc", "mcause", "a0", "tval"))

  /** 调用异常处理 DPI
    * @param en 使能信号
    * @param pc 程序计数器
    * @param mcause 异常原因
    * @param a0 a0 寄存器值
    */
  def apply(en: Bool, pc: UInt, mcause: UInt, a0: UInt, tval: UInt): Unit =
    super.call(Cat(0.U(31.W), en.asUInt), pc, mcause.pad(32), a0, tval)
}
