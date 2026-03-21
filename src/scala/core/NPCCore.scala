package ysyx.core

import chisel3._
import chisel3.util._
import chisel3.probe.{define, Probe, ProbeValue}
import freechips.rocketchip.amba.axi4._

import ysyx.core.common._
import ysyx.core.backend._
import ysyx.core.frontend._
import ysyx.core.sram.SRAMBundle

class DebugBundle extends NPCBundle {
  val pc = UInt(dataBits.W)
  val dnpc = UInt(dataBits.W)
  val inst = UInt(instBits.W)
  val is_mmio = Bool()
  val gpr = Vec(NRReg, UInt(dataBits.W))
  val csr = new CSRUDebugBundle
  val perf = new PerfBundle
}

class NPCCore extends NPCModule {

  val icache = IO(SRAMBundle(sramParams))
  val dcache = IO(SRAMBundle(sramParams))
  val perip = IO(SRAMBundle(sramParams))
  val interrupt = IO(Input(Bool()))
  val fence_i = IO(Output(Bool()))

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
  val probe = IO(chiselTypeOf(be.probe))
  probe := be.probe
}
