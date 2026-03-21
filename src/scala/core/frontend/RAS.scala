package ysyx.core.frontend

import chisel3._
import chisel3.util._

import ysyx.core.common._

class RASPushPort extends NPCBundle {
  val dnpc = UInt(addrBits.W)
}

class RASPopPort extends NPCBundle {
  val dnpc = Input(UInt(addrBits.W))
}

// return address stack
class RAS(entries: Int = 8) extends NPCModule {

  require(isPow2(entries))
  
  private val entriesBits: Int = log2Ceil(entries)

  val io = IO(new Bundle {
    val pop = Flipped(Valid(new RASPopPort))
    val push = Flipped(Valid(new RASPushPort))
  })

  val stack = Mem(entries, UInt(addrBits.W))
  val top = RegInit(0.U(entriesBits.W)) // could be wrapping to 0

  assert(
    !(io.pop.valid && io.push.valid),
    "pop and push should not be valid at the same time"
  )

  when( io.push.valid ) {
    top := top + 1.U
    stack(top) := io.push.bits.dnpc
  }

  when( io.pop.valid ) {
    top := top - 1.U
    io.pop.bits.dnpc := stack(top)
  }

}
