package ysyx.core.component

import chisel3._
import chisel3.util._
import chisel3.probe.{define, Probe, ProbeValue}
import ysyx.core.common._

class RFUOutput extends NPCBundle {
  val rs1_v  = UInt(dataBits.W)
  val rs2_v  = UInt(dataBits.W)
}

class RFUInput extends NPCBundle {
  val rs1_i = UInt(NRRegbits.W)
  val rs2_i = UInt(NRRegbits.W)
  val rd_i  = UInt(NRRegbits.W)
  // write
  val wdata = UInt(dataBits.W)
  val wen   = Bool()
}

class RFU extends NPCModule {
  val io = IO(new Bundle {
    val in   = Flipped(new RFUInput)
    val out  = new RFUOutput
    val probe = Output(Probe(Vec(NRReg, UInt(dataBits.W))))
  })

  private val rf = RegInit(VecInit(Seq.fill(NRReg)(0.U(dataBits.W))))

  io.out.rs1_v := Mux(io.in.rs1_i === 0.U, 0.U, rf(io.in.rs1_i))
  io.out.rs2_v := Mux(io.in.rs2_i === 0.U, 0.U, rf(io.in.rs2_i))

  when(io.in.wen && (io.in.rd_i =/= 0.U)) { rf(io.in.rd_i) := io.in.wdata }

  define(io.probe, ProbeValue(rf))
}
