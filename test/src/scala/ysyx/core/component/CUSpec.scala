package ysyx.core.component

import chisel3._
import chisel3.util._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import ysyx.core.common.Instructions._

/** CU 译码单元测试，可用 VSCode Metals 的 Run Test 运行 */
class CUSpec extends AnyFlatSpec with Matchers {

  "CU decode table" should "contain expected number of instructions" in {
    val value: BigInt = 52
    println(value.toString(2))
  }

}
