package ysyx.core.frontend

import chisel3._
import chisel3.util._
import ysyx.core.common._

// branch target buffer
class BTBUpdate extends NPCBundle {
  val pc = UInt(addrBits.W)
  val dnpc = UInt(addrBits.W)
}

class BTBEntry extends BTBUpdate {
  val occupied = Bool()
}

class BTBReadPort extends NPCBundle {
  val pc = UInt(addrBits.W)
  val dnpc = Input(UInt(addrBits.W))
  val hit = Input(Bool())
}

class BTB(entries: Int = 4) extends NPCModule {
  require(isPow2(entries))
  private val entriesBits = log2Ceil(entries)

  val io = IO(new Bundle {
    val lookup = Flipped(new BTBReadPort)
    val update = Flipped(Valid(new BTBUpdate))
  })

  val cam = Reg(Vec(entries, new BTBEntry))

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

// branch history table
class BHTUpdate extends NPCBundle {
  val br_flag = Bool()
}

// from the master's view of point
class RASUpdate extends NPCBundle {
  val is_call = Bool()
  val is_ret = Bool()
}
