package ysyx.core.frontend

import chisel3._
import chisel3.util._

import ysyx.core.common._

// gshare branch predictor
class BHTUpdate extends NPCBundle {
  val br_flag = Bool()
  val pc = UInt()
  val ghr = UInt(ghrBits.W)
}

class BHTReadPort extends NPCBundle {
  val pc = UInt(addrBits.W)
  val br_flag = Input(Bool())
  val ghr = Input(UInt(ghrBits.W))
  val hit = Input(Bool())
}

class BHTEntry(countBits: Int = 2) extends NPCBundle {
  val occupied = Bool()
  val count = UInt(countBits.W)
}

// GShare
class BHT(entries: Int = 1 << 12, countBits: Int = 2) extends NPCModule {
  require(isPow2(entries))
  private val entriesBits = log2Ceil(entries)

  val io = IO(new Bundle {
    val update = Flipped(Valid(new BHTUpdate))
    val lookup = Flipped(new BHTReadPort)
  })

  val ghr = RegInit(0.U(entriesBits.W)) // global history register (shift register)
  val pht = Reg(Vec(entries, new BHTEntry(countBits))) // pattern history table

  private def extractIdx(pc: UInt): UInt = {
    val lsb: Int = 2
    val msb: Int = entriesBits + 2 - 1
    pc(msb, lsb)
  }

  // lookup
  val idx = extractIdx(io.lookup.pc) ^ ghr
  io.lookup.br_flag := pht(idx).count(countBits - 1)
  io.lookup.ghr := ghr
  io.lookup.hit := pht(idx).occupied

  // update
  when(io.update.valid) {
    val u = io.update.bits
    val idx = extractIdx(u.pc) ^ u.ghr
    val count = pht(idx).count
    val max = ((1 << countBits) - 1).U
    when(u.br_flag) {
      count := Mux(count === max, max, count + 1.U)
    }.otherwise {
      count := Mux(count === 0.U, 0.U, count - 1.U)
    }
    pht(idx).occupied := true.B
    ghr := Cat(ghr(entriesBits - 2, 0), u.br_flag)
  }

}
