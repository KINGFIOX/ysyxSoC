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
  val iptw_port = IO(SRAMBundle(sramParams))
  val dptw_port = IO(SRAMBundle(sramParams))
  val ext_irq = IO(Input(Bool()))
  val mtime_in = IO(Input(UInt(64.W)))
  val fence_i = IO(Output(Bool()))
  val sfence_vma = IO(Output(Bool()))

  // modules
  val fe = Module(new FrontEnd)
  val be = Module(new BackEnd)
  val instQueue = Queue(fe.io.out, entries = 4, flush = Some(be.io.flush))
  be.io.in <> instQueue
  fe.io.redirect := be.io.redirect
  fe.io.flush := be.io.flush

  // bus
  fe.icache <> icache
  fe.ptw_port <> iptw_port
  be.dcache <> dcache
  be.perip <> perip
  be.ptw_port <> dptw_port

  // interrupts
  be.ext_irq := ext_irq
  be.mtime_in := mtime_in

  // fence
  fence_i := be.fence_i
  sfence_vma := be.sfence_vma

  // MMU signals: BackEnd -> FrontEnd
  fe.io.satp := be.satp_out
  fe.io.priv := be.priv_out
  fe.io.sfence_vma := be.sfence_vma

  // probe
  val probe = IO(chiselTypeOf(be.probe))
  probe := be.probe
}
