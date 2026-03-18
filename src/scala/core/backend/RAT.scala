package ysyx.core.backend

import chisel3._
import chisel3.util._

import ysyx.core.common._

// from the master's point-of-view
class RATReadPort extends NPCBundle {
  val addr = Output(UInt(NRRegbits.W))
  // zero is always not busy
  val busy = Input(Bool())
  val tag  = Input(UInt(robEntryBits.W))
}

class RATWritePort extends NPCBundle {
  val en   = Bool()
  val addr = UInt(NRRegbits.W)
  val tag  = UInt(robEntryBits.W)
}

class RATCommitPort extends NPCBundle {
  val en   = Bool()
  val addr = UInt(NRRegbits.W)
  val tag  = UInt(robEntryBits.W)
}

class RAT(val numReadPorts: Int = 2) extends NPCModule {
  val io = IO(new Bundle {
    val rename  = Flipped(Vec(numReadPorts, new RATReadPort))
    val disp = Flipped(new RATWritePort)
    val commit = Flipped(new RATCommitPort)
    val flush = Input(Bool())
  })

  private val busy = RegInit(VecInit(Seq.fill(NRReg)(false.B))) // inflight
  private val tags = Mem(NRReg, UInt(robEntryBits.W)) // if inflight

  // Priority: flush > write (dispatch) > commit (clear)
  when(io.flush) {
    busy := VecInit(Seq.fill(NRReg)(false.B))
  }.otherwise {
    when(io.commit.en && io.commit.addr =/= 0.U) {
      when(tags(io.commit.addr) === io.commit.tag) {
        busy(io.commit.addr) := false.B
      }
    }
    when(io.disp.en && io.disp.addr =/= 0.U) {
      busy(io.disp.addr) := true.B
      tags(io.disp.addr) := io.disp.tag
    }
  }

  for (i <- 0 until numReadPorts) {
    io.rename(i).busy := busy(io.rename(i).addr) && io.rename(i).addr =/= 0.U
    io.rename(i).tag := tags(io.rename(i).addr)
  }
}
