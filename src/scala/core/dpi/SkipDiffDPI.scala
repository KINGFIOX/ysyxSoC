package ysyx.core.dpi

import chisel3._
import chisel3.util.Cat
import chisel3.util.circt.dpi._

/** Difftest 跳过参考模型的 DPI 函数
  *
  * 对应 C 函数签名:
  * extern "C" void difftest_skip_ref_dpi(int en);
  */
object DifftestSkipRef extends DPIClockedVoidFunctionImport {
  override val functionName = "difftest_skip_ref_dpi"
  override val inputNames = Some(Seq("en"))

  /** 调用 difftest 跳过参考模型
    * @param en 使能信号
    */
  def apply(en: Bool): Unit =
    super.call(Cat(0.U(31.W), en.asUInt))
}
