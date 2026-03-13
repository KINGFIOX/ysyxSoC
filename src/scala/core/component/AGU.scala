package ysyx.core.component

import chisel3._
import chisel3.util._
import ysyx.core.common._

class AGUInput extends NPCBundle {
  val base   = UInt(addrBits.W)
  val offset = UInt(addrBits.W)
}

class AGUOutput extends NPCBundle {
  val addr = UInt(addrBits.W)
}

class AGU extends ExecUnit(new AGUInput, new AGUOutput) {
  io.out.addr := io.in.base + io.in.offset
}

class AGUExtra extends NPCBundle {
  val imm = UInt(dataBits.W)
}

class AGUIssueQueue
    extends IssueQueue(new AGUExtra, entries = 4, bypassCDB1InIssue = true)
