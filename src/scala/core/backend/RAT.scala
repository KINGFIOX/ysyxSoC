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
  val addr = UInt(NRRegbits.W)
  val tag  = UInt(robEntryBits.W)
}

class RATCommitPort extends NPCBundle {
  val addr = UInt(NRRegbits.W)
  val tag  = UInt(robEntryBits.W)
}

class RAT(val numReadPorts: Int = 2) extends NPCModule {
  val io = IO(new Bundle {
    val rename  = Flipped(Vec(numReadPorts, new RATReadPort))
    val disp = Flipped(Valid(new RATWritePort)) // forward
    val commit = Flipped(Valid(new RATCommitPort))
    val flush = Input(Bool())
  })

  private val busy = RegInit(VecInit(Seq.fill(NRReg)(false.B))) // inflight
  private val tags = Mem(NRReg, UInt(robEntryBits.W)) // if inflight

  // Priority: flush > write (dispatch) > commit (clear)
  when(io.flush) {
    busy := VecInit(Seq.fill(NRReg)(false.B))
  }.otherwise {
    when(io.commit.valid && io.commit.bits.addr =/= 0.U) {
      when(tags(io.commit.bits.addr) === io.commit.bits.tag) {
        busy(io.commit.bits.addr) := false.B
      }
    }
    when(io.disp.valid && io.disp.bits.addr =/= 0.U) {
      busy(io.disp.bits.addr) := true.B
      tags(io.disp.bits.addr) := io.disp.bits.tag
    }
  }

  for (i <- 0 until numReadPorts) {
    val raddr = io.rename(i).addr
    val disp_hit = io.disp.valid && io.disp.bits.addr === raddr && raddr =/= 0.U
    io.rename(i).busy := Mux(disp_hit, true.B, busy(raddr) && raddr =/= 0.U)
    io.rename(i).tag  := Mux(disp_hit, io.disp.bits.tag, tags(raddr))
  }
}
