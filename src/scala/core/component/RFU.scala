package ysyx.core.component

import chisel3._
import chisel3.util._
import ysyx.core.common.HasCoreParameter
import ysyx.core.common.HasRegFileParameter

class RFUOutputBundle extends Bundle with HasCoreParameter with HasRegFileParameter {
  val rs1_v  = UInt(XLEN.W)
  val rs2_v  = UInt(XLEN.W)
  val debug = new RFUDebugBundle
}

class RFUDebugBundle extends Bundle with HasCoreParameter with HasRegFileParameter {
  val gpr = Vec(NRReg, UInt(XLEN.W))
}

class RFUInputBundle extends Bundle with HasRegFileParameter with HasCoreParameter {
  val rs1_i = UInt(NRRegbits.W)
  val rs2_i = UInt(NRRegbits.W)
  val rd_i  = UInt(NRRegbits.W)
  // write
  val wdata = UInt(XLEN.W)
  val wen   = Bool()
}

/** @brief
  *   寄存器堆
  */
class RFU extends Module with HasCoreParameter with HasRegFileParameter {
  val io = IO(new Bundle {
    val in  = Flipped(new RFUInputBundle)
    val out = new RFUOutputBundle
  })

  // 使用 RegInit 初始化为 0
  private val rf = RegInit(VecInit(Seq.fill(NRReg)(0.U(XLEN.W))))

  // 读取: x0 始终为 0
  io.out.rs1_v := Mux(io.in.rs1_i === 0.U, 0.U, rf(io.in.rs1_i))
  io.out.rs2_v := Mux(io.in.rs2_i === 0.U, 0.U, rf(io.in.rs2_i))

  // 写入: x0 不可写
  when(io.in.wen && (io.in.rd_i =/= 0.U)) { rf(io.in.rd_i) := io.in.wdata }

  // 导出所有寄存器用于 difftest (带 bypass)
  // 如果当前周期正在写入某个寄存器，debug 输出应该是新值
  for (i <- 0 until NRReg) {
    io.out.debug.gpr(i) := Mux(
      io.in.wen && (io.in.rd_i === i.U) && (i.U =/= 0.U),
      io.in.wdata,  // bypass: 输出即将写入的新值
      rf(i)         // 否则输出寄存器当前值
    )
  }
}
