package ysyx.core.frontend

import chisel3._
import chisel3.util._

import ysyx.core.common._

// branch history table
class BHTUpdate extends NPCBundle {
  val br_flag = Bool() // jmp or not
  val pc = UInt()
}

class BHTEntry(tagBits: Int, countBits: Int) extends NPCBundle {
  val count = UInt(countBits.W) // jmp or not
  val occupied = Bool()
  val tag = UInt(tagBits.W)
}

class BHTReadPort extends NPCBundle {
  val pc = UInt(addrBits.W)
  val br_flag = Input(Bool())
  val hit = Input(Bool())
}

class BHT(entries: Int = 4096, countBits: Int = 2) extends NPCModule {
  require(isPow2(entries))
  private val entriesBits = log2Ceil(entries)
  private val tagBits = addrBits - entriesBits - 2 /*pc would be aligned to 2*/

  val io = IO(new Bundle {
    val update = Flipped(Valid(new BHTUpdate))
    val lookup = Flipped(new BHTReadPort)
  })

  val lht = Reg( Vec(entries, new BHTEntry(tagBits, countBits))) // local history table

  private def extractIdx(pc: UInt): UInt = {
    val lsb: Int = 2
    val msb: Int = entriesBits + 2 - 1
    pc(msb, lsb)
  }

  private def extractTag(pc: UInt): UInt = {
    val lsb: Int = 2 + entriesBits
    val msb: Int = 2 + entriesBits + tagBits - 1
    pc(msb, lsb)
  }

  // lookup
  val idx = extractIdx(io.lookup.pc)
  val tag = extractTag(io.lookup.pc)
  io.lookup.br_flag := lht(idx).count( countBits - 1) // msb to indicate jmp or not
  io.lookup.hit := lht(idx).occupied && (lht(idx).tag === tag)

  // update
  when(io.update.valid) {
    val u = io.update.bits
    val idx = extractIdx(u.pc)
    val tag = extractTag(u.pc)
    val c = lht(idx).count
    val max = ((1 << countBits) - 1).U
    when(u.br_flag) {
      lht(idx).count := Mux(c === max, max, c + 1.U)
    }.otherwise {
      lht(idx).count := Mux(c === 0.U, 0.U, c - 1.U)
    }
    lht(idx).tag := tag
    lht(idx).occupied := true.B
  }

}
