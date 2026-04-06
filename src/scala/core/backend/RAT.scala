package ysyx.core.backend

import chisel3._
import chisel3.util._

import ysyx.core.common._

class FutureRATReadPort extends NPCBundle {
  val addr = Output(UInt(NRRegbits.W))
  val preg = Input(UInt(NRPhyRegBits.W))
}

class FutureRATWritePort extends NPCBundle {
  val addr = UInt(NRRegbits.W)
  val preg = UInt(NRPhyRegBits.W)
}

class FutureRAT(val numReadPorts: Int = 3) extends NPCModule {
  val io = IO(new Bundle {
    val read = Vec(numReadPorts, Flipped(new FutureRATReadPort)) // rs1, rs2, rd(old_prd)
    val write = Flipped(Valid(new FutureRATWritePort))
    val flush = Input(Bool())
    val arch_snapshot = Input(Vec(NRReg, UInt(NRPhyRegBits.W)))
  })

  // arch_reg[i] = phys_reg: initial identity mapping x0->p0, x1->p1, ..., x31->p31
  val table = RegInit(VecInit((0 until NRReg).map(_.U(NRPhyRegBits.W))))

  when(io.flush) {
    table := io.arch_snapshot
  }.elsewhen(io.write.valid && io.write.bits.addr =/= 0.U) {
    table(io.write.bits.addr) := io.write.bits.preg
  }

  for (i <- 0 until numReadPorts) {
    io.read(i).preg := table(io.read(i).addr)
  }
}

class ArchRATWritePort extends NPCBundle {
  val addr = UInt(NRRegbits.W)
  val preg = UInt(NRPhyRegBits.W)
}

class ArchRAT extends NPCModule {
  val io = IO(new Bundle {
    val write = Flipped(Valid(new ArchRATWritePort))
    val snapshot = Output(Vec(NRReg, UInt(NRPhyRegBits.W)))
  })

  val table = RegInit(VecInit((0 until NRReg).map(_.U(NRPhyRegBits.W))))

  when(io.write.valid && io.write.bits.addr =/= 0.U) {
    table(io.write.bits.addr) := io.write.bits.preg
  }

  // Write forwarding: flush consumers (FutureRAT, FreeList) see the
  // current-cycle commit write so that a mispredicted JALR that both
  // writes rd and triggers flush is correctly reflected.
  io.snapshot := table
  when(io.write.valid && io.write.bits.addr =/= 0.U) {
    io.snapshot(io.write.bits.addr) := io.write.bits.preg
  }
}
