package ysyx.core.backend

import chisel3._
import chisel3.util._

import ysyx.core.common._

// from the master's point-of-view
class RFUReadPort extends NPCBundle {
  val addr = Output(UInt(NRRegbits.W))
  val data = Input(UInt(dataBits.W))
}

class RFUWritePort extends NPCBundle {
  val addr = UInt(NRRegbits.W)
  val data = UInt(dataBits.W)
}

class RFU(val numReadPorts: Int = 2) extends NPCModule {
  val probe = IO(Vec(NRReg, UInt(dataBits.W)))
  val io = IO(new Bundle {
    val read = Vec(numReadPorts, Flipped(new RFUReadPort))
    val write = Flipped(Valid(new RFUWritePort))
  })

  val regfile = RegInit(VecInit(Seq.fill(NRReg)(0.U(dataBits.W))))

  for (i <- 0 until numReadPorts) {
    io.read(i).data := Mux(io.read(i).addr === 0.U, 0.U, regfile(io.read(i).addr))
  }

  when(io.write.valid && (io.write.bits.addr =/= 0.U)) {
    regfile(io.write.bits.addr) := io.write.bits.data
  }

  probe := regfile
}
