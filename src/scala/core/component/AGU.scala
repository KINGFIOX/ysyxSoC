package ysyx.core.component

import chisel3._
import chisel3.util._
import ysyx.core.common._

class AGUInput extends NPCBundle {
  val offset = UInt(addrBits.W)
  val base = UInt(addrBits.W)
}

class AGUOutput extends NPCBundle {
  val addr = UInt(addrBits.W)
}

// address generation unit
class AGU extends NPCModule {
  val io = IO(new Bundle {
    val in = Flipped(Decoupled(new AGUInput))
    val out = Decoupled(new AGUOutput)
  })

  io.in.ready := io.out.ready
  io.out.valid := io.in.valid
  io.out.bits.addr := io.in.bits.base + io.in.bits.offset

}
