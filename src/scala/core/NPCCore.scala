package ysyx.core

import chisel3._
import chisel3.util._
import chisel3.probe.{define, Probe, ProbeValue}
import freechips.rocketchip.amba.axi4._

import ysyx.core.common._
import ysyx.core.backend._
import ysyx.core.frontend._

class DebugBundle extends NPCBundle {
  val valid = Bool()
  val pc = UInt(dataBits.W)
  val dnpc = UInt(dataBits.W)
  val inst = UInt(instBits.W)
  val is_mmio = Bool()
  val gpr = Vec(NRReg, UInt(dataBits.W))
  val csr = new CSRUDebugBundle
}

class NPCCore extends NPCModule {

  val icache    = IO(AXI4Bundle(axiParams))
  val dcache    = IO(AXI4Bundle(axiParams))
  val perip     = IO(AXI4Bundle(axiParams))
  val probe     = IO(Output(new DebugBundle))
  val interrupt = IO(Input(Bool()))
  val fence_i   = IO(Output(Bool()))

  // modules
  val fe = Module(new FrontEnd)
  val be = Module(new BackEnd)
  val instQueue = Queue(fe.io.out, entries = 4, flush = Some(be.io.flush))
  be.io.in <> instQueue
  fe.io.redirect := be.io.redirect

  // bus
  fe.icache <> icache
  be.dcache <> dcache
  be.perip <> perip

  // int
  be.interrupt := interrupt

  // fence
  fence_i := be.fence_i

  // probe
  probe := be.probe
}
