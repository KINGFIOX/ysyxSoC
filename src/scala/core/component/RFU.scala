package ysyx.core.component

import chisel3._
import chisel3.util._
import chisel3.probe.{define, Probe, ProbeValue}
import ysyx.core.common.HasCoreParameter
import ysyx.core.common.HasRegFileParameter

class RFUOutputBundle extends Bundle with HasCoreParameter with HasRegFileParameter {
  val rs1_v  = UInt(dataBits.W)
  val rs2_v  = UInt(dataBits.W)
}

class RFUInputBundle extends Bundle with HasRegFileParameter with HasCoreParameter {
  val rs1_i = UInt(NRRegbits.W)
  val rs2_i = UInt(NRRegbits.W)
  val rd_i  = UInt(NRRegbits.W)
  // write
  val wdata = UInt(dataBits.W)
  val wen   = Bool()
}

/** @brief
  *   寄存器堆
  */
class RFU extends Module with HasCoreParameter with HasRegFileParameter {
  val io = IO(new Bundle {
    val in   = Flipped(new RFUInputBundle)
    val out  = new RFUOutputBundle
    val probe = Output(Probe(Vec(NRReg, UInt(dataBits.W))))
  })

  // 使用 RegInit 初始化为 0
  private val rf = RegInit(VecInit(Seq.fill(NRReg)(0.U(dataBits.W))))

  // 读取: x0 始终为 0
  io.out.rs1_v := Mux(io.in.rs1_i === 0.U, 0.U, rf(io.in.rs1_i))
  io.out.rs2_v := Mux(io.in.rs2_i === 0.U, 0.U, rf(io.in.rs2_i))

  // 写入: x0 不可写
  when(io.in.wen && (io.in.rd_i =/= 0.U)) { rf(io.in.rd_i) := io.in.wdata }

  // probe
  define(io.probe, ProbeValue(rf))
}
