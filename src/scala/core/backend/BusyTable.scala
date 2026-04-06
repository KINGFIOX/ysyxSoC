package ysyx.core.backend

import chisel3._
import chisel3.util._

import ysyx.core.common._

class WakeupPort extends NPCBundle {
  val prd = UInt(NRPhyRegBits.W)
}

class BusyTableReadPort extends NPCBundle {
  val addr = Output(UInt(NRPhyRegBits.W))
  val busy = Input(Bool())
}

// for value ready
class BusyTable(val numReadPorts: Int = 2, val numWakeupPorts: Int = 3) extends NPCModule {
  val io = IO(new Bundle {
    val set_busy = Flipped(Valid(UInt(NRPhyRegBits.W)))
    val read = Vec(numReadPorts, Flipped(new BusyTableReadPort))
    val wakeup = Vec(numWakeupPorts, Flipped(Valid(new WakeupPort)))
    val flush = Input(Bool())
  })

  val busy = RegInit(VecInit(Seq.fill(NRPhyReg)(false.B)))

  when(io.flush) {
    busy := VecInit(Seq.fill(NRPhyReg)(false.B))
  }.otherwise {
    for (wk <- io.wakeup) {
      when(wk.valid && wk.bits.prd =/= 0.U) {
        busy(wk.bits.prd) := false.B
      }
    }
    when(io.set_busy.valid && io.set_busy.bits =/= 0.U) {
      busy(io.set_busy.bits) := true.B
    }
  }

  for (i <- 0 until numReadPorts) {
    val addr = io.read(i).addr
    val set_hit = io.set_busy.valid && io.set_busy.bits === addr && addr =/= 0.U
    val wakeup_hit = io.wakeup.map(wk => wk.valid && wk.bits.prd === addr).reduce(_ || _)
    io.read(i).busy := Mux(set_hit, true.B, Mux(wakeup_hit, false.B, busy(addr) && addr =/= 0.U))
  }
}
