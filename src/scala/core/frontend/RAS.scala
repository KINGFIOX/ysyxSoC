package ysyx.core.frontend

import chisel3._
import chisel3.util._

import ysyx.core.common._

class RASPushPort extends NPCBundle {
  val dnpc = UInt(addrBits.W)
}

class RASPopPort extends NPCBundle {
  val dnpc = UInt(addrBits.W)
}

// return address stack
class RAS(entries: Int = 8) extends NPCModule {

  require(isPow2(entries))
  
  private val entriesBits: Int = log2Ceil(entries)

  val io = IO(new Bundle {
    val pop = new Bundle {
      val valid = Input(Bool())
      val bits = Output(new RASPopPort)
    }
    val push = Flipped(Valid(new RASPushPort))
  })

  val stack = Reg(Vec(entries, UInt(addrBits.W)))
  val top = RegInit(0.U(entriesBits.W))

  assert(
    !(io.pop.valid && io.push.valid),
    "pop and push should not be valid at the same time"
  )

  io.pop.bits.dnpc := stack(top - 1.U)

  when( io.push.valid ) {
    top := top + 1.U
    stack(top) := io.push.bits.dnpc
  }

  when( io.pop.valid ) {
    top := top - 1.U
  }

}
