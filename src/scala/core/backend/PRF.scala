package ysyx.core.backend

import chisel3._
import chisel3.util._

import ysyx.core.common._

class PRFReadPort extends NPCBundle {
  val addr = Output(UInt(NRPhyRegBits.W))
  val data = Input(UInt(dataBits.W))
}

class PRFWritePort extends NPCBundle {
  val addr = UInt(NRPhyRegBits.W)
  val data = UInt(dataBits.W)
}

class PRF(val numReadPorts: Int = 6, val numWritePorts: Int = 4) extends NPCModule {
  val io = IO(new Bundle {
    val read = Vec(numReadPorts, Flipped(new PRFReadPort))
    val write = Vec(numWritePorts, Flipped(Valid(new PRFWritePort)))
  })

  val regfile = Reg(Vec(NRPhyReg, UInt(dataBits.W)))

  // read
  for (i <- 0 until numReadPorts) {
    io.read(i).data := Mux(io.read(i).addr === 0.U, 0.U, regfile(io.read(i).addr))
  }

  // write
  for (i <- 0 until numWritePorts) {
    when(io.write(i).valid && io.write(i).bits.addr =/= 0.U) {
      regfile(io.write(i).bits.addr) := io.write(i).bits.data
    }
  }

  // probe
  val probe = IO(new Bundle {
    val arch_rat = Input(Vec(NRReg, UInt(NRPhyRegBits.W)))
    val gpr = Output(Vec(NRReg, UInt(dataBits.W)))
  })
  for (i <- 0 until NRReg) {
    probe.gpr(i) := Mux(i.U === 0.U, 0.U, regfile(probe.arch_rat(i)))
  }
}
