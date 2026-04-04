package ysyx.core.backend

import chisel3._
import chisel3.util._

import ysyx.core.common._

class AGUInput extends NPCBundle {
  val base = UInt(addrBits.W)
  val offset = UInt(addrBits.W)
  val rob_tag = UInt(robEntryBits.W)
  val wdata = UInt(dataBits.W)
}

class AGUOutput extends NPCBundle {
  val addr = UInt(addrBits.W)
  val is_mmio = Bool()
  val rob_tag = UInt(robEntryBits.W)
  val wdata = UInt(dataBits.W)
}

class AGU extends ExecUnit(new AGUInput, new AGUOutput) {
  io.in.ready := io.out.ready
  io.out.valid := io.in.valid
  val addr = io.in.bits.base + io.in.bits.offset
  io.out.bits.addr := addr
  io.out.bits.is_mmio := AddressMap.is_mmio(addr)
  io.out.bits.rob_tag := io.in.bits.rob_tag
  io.out.bits.wdata := io.in.bits.wdata
}

class AGUExtra extends NPCBundle {
  val offset = UInt(dataBits.W)
}

class AGUIssueQueue extends IssueQueue(new AGUExtra, entries = 4)
