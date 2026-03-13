package ysyx.core.backend

import chisel3._
import chisel3.util._

import ysyx.core.common._

class AGUInput extends NPCBundle {
  val base = UInt(addrBits.W)
  val offset = UInt(addrBits.W)
}

class AGUOutput extends NPCBundle {
  val addr = UInt(addrBits.W)
}

class AGU extends ExecUnit(new AGUInput, new AGUOutput) {
  io.in.ready := io.out.ready
  io.out.valid := io.in.valid
  io.out.bits.addr := io.in.bits.base + io.in.bits.offset
}

class AGUExtra extends NPCBundle {
  val imm = UInt(dataBits.W)
}

class AGUIssueQueue extends IssueQueue(new AGUExtra, entries = 4)
