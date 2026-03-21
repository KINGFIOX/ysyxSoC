package ysyx.core.frontend

import chisel3._
import chisel3.util._

import ysyx.core.common._

// gshare branch predictor
class BHTUpdate(ghrBits: Int) extends NPCBundle {
  val br_flag = Bool()
  val pc = UInt()
  val ghr = UInt(ghrBits.W) // ght snapshot at prediction time
}

class BHTReadPort(ghrBits: Int) extends NPCBundle {
  val pc = UInt(addrBits.W)
  val br_flag = Input(Bool())
  val ghr = Input(UInt(ghrBits.W))
}

class BHT(entries: Int = 4096, countBits: Int = 2) extends NPCModule {
  require(isPow2(entries))
  private val entriesBits = log2Ceil(entries)

  val io = IO(new Bundle {
    val update = Flipped(Valid(new BHTUpdate(entriesBits)))
    val lookup = Flipped(new BHTReadPort(entriesBits))
  })

  val ght = RegInit(0.U(entriesBits.W)) // global history table
  val pht = Reg(Vec(entries, UInt(countBits.W))) // pattern history table

  private def extractIdx(pc: UInt): UInt = {
    val lsb: Int = 2
    val msb: Int = entriesBits + 2 - 1
    pc(msb, lsb)
  }

  // lookup
  val idx = extractIdx(io.lookup.pc) ^ ght
  io.lookup.br_flag := pht(idx)(countBits - 1)
  io.lookup.ghr := ght

  // update
  when(io.update.valid) {
    val u = io.update.bits
    val idx = extractIdx(u.pc) ^ u.ghr
    val c = pht(idx)
    val max = ((1 << countBits) - 1).U
    when(u.br_flag) {
      pht(idx) := Mux(c === max, max, c + 1.U)
    }.otherwise {
      pht(idx) := Mux(c === 0.U, 0.U, c - 1.U)
    }
    ght := Cat(ght(entriesBits - 2, 0), u.br_flag)
  }

}
