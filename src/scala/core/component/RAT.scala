package ysyx.core.component

import chisel3._
import chisel3.util._
import ysyx.core.common.{HasCoreParameter, HasRegFileParameter}

class RATReadPort extends Bundle with HasCoreParameter with HasRegFileParameter {
  val addr = Input(UInt(NRRegbits.W))
  val busy = Output(Bool())
  val tag  = Output(UInt(robEntryBits.W))
}

class RATWritePort extends Bundle with HasCoreParameter with HasRegFileParameter {
  val en   = Input(Bool())
  val addr = Input(UInt(NRRegbits.W))
  val tag  = Input(UInt(robEntryBits.W))
}

class RATCommitPort extends Bundle with HasCoreParameter with HasRegFileParameter {
  val en   = Input(Bool())
  val addr = Input(UInt(NRRegbits.W))
  val tag  = Input(UInt(robEntryBits.W))
}

class RAT extends Module with HasCoreParameter with HasRegFileParameter {
  val io = IO(new Bundle {
    val read1  = new RATReadPort
    val read2  = new RATReadPort
    val write  = new RATWritePort
    val commit = new RATCommitPort
    val flush  = Input(Bool())
  })

  private val busy = RegInit(VecInit(Seq.fill(NRReg)(false.B))) // inflight
  private val tags = Reg(Vec(NRReg, UInt(robEntryBits.W))) // if inflight

  // Priority: flush > write (dispatch) > commit (clear)
  when(io.flush) {
    busy := VecInit(Seq.fill(NRReg)(false.B))
  }.otherwise {
    when(io.commit.en && io.commit.addr =/= 0.U) {
      when(tags(io.commit.addr) === io.commit.tag) {
        busy(io.commit.addr) := false.B
      }
    }
    when(io.write.en && io.write.addr =/= 0.U) {
      busy(io.write.addr) := true.B
      tags(io.write.addr) := io.write.tag
    }
  }

  io.read1.busy := busy(io.read1.addr) && io.read1.addr =/= 0.U
  io.read1.tag  := tags(io.read1.addr)

  io.read2.busy := busy(io.read2.addr) && io.read2.addr =/= 0.U
  io.read2.tag  := tags(io.read2.addr)
}
