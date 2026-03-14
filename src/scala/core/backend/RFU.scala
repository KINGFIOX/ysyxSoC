package ysyx.core.backend

import chisel3._
import chisel3.util._
import chisel3.probe.{define, Probe, ProbeValue}

import ysyx.core.common._

class RFUReadPort extends NPCBundle {
  val addr = Input(UInt(NRRegbits.W))
  val data = Output(UInt(dataBits.W))
}

class RFUWritePort extends NPCBundle {
  val addr = Input(UInt(NRRegbits.W))
  val data = Input(UInt(dataBits.W))
  val en = Input(Bool())
}

class RFU(val numReadPorts: Int = 2) extends NPCModule {
  val io = IO(new Bundle {
    val read = Vec(numReadPorts, new RFUReadPort)
    val write = new RFUWritePort
    val probe = Output(Probe(Vec(NRReg, UInt(dataBits.W))))
  })

  private val rf = RegInit(VecInit(Seq.fill(NRReg)(0.U(dataBits.W))))

  for (i <- 0 until numReadPorts) {
    io.read(i).data := Mux(io.read(i).addr === 0.U, 0.U, rf(io.read(i).addr))
  }

  when(io.write.en && (io.write.addr =/= 0.U)) {
    rf(io.write.addr) := io.write.data
  }

  define(io.probe, ProbeValue(rf))
}
