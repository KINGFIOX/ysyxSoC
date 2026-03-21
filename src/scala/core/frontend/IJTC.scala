package ysyx.core.frontend

import chisel3._
import chisel3.util._
import ysyx.core.common._

class IJTCUpdate extends NPCBundle {
  val pc = UInt(addrBits.W)
  val dnpc = UInt(addrBits.W)
}

class IJTCEntry extends IJTCUpdate {
  val occupied = Bool()
}

class IJTCReadPort extends NPCBundle {
  val pc = UInt(addrBits.W)
  val dnpc = Input(UInt(addrBits.W))
  val hit = Input(Bool())
}

// indirect jump target cache, excluding `ret`
class IJTC(entries: Int = 8) extends NPCModule {
  require(isPow2(entries))
  private val entriesBits = log2Ceil(entries)

  val io = IO(new Bundle {
    val update = Flipped(Valid(new IJTCUpdate))
    val lookup = Flipped(new IJTCReadPort)
  })

  val cam = Reg(Vec(entries, new IJTCEntry))

  val evict = RegInit(0.U(entriesBits.W))

  val hits = VecInit(cam.map(e => e.occupied && e.pc === io.lookup.pc))
  val hitIdx = PriorityEncoder(hits.asUInt)

  io.lookup.hit := hits.reduce(_ || _)
  io.lookup.dnpc := cam(hitIdx).dnpc

  // update: on valid update, write-hit updates in place; write-miss evicts FIFO
  when(io.update.valid) {
    val u = io.update.bits
    val matchVec = VecInit(cam.map(e => e.occupied && e.pc === u.pc))
    val alreadyPresent = matchVec.asUInt.orR
    val matchIdx = PriorityEncoder(matchVec.asUInt)

    val writeIdx = Mux(alreadyPresent, matchIdx, evict)

    cam(writeIdx).occupied := true.B
    cam(writeIdx).pc := u.pc
    cam(writeIdx).dnpc := u.dnpc

    when(!alreadyPresent) { // miss
      evict := evict +% 1.U
    }
  }
}
