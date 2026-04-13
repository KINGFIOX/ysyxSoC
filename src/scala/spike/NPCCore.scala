package ysyx.spike

import chisel3._
import chisel3.util._

import ysyx.core.common._
import ysyx.core.backend.BackEnd
import ysyx.core.sram.SRAMBundle

class NPCCore extends NPCModule {

  val icache = IO(SRAMBundle(sramParams))
  val dcache = IO(SRAMBundle(sramParams))
  val perip = IO(SRAMBundle(sramParams))
  val iptw_port = IO(SRAMBundle(sramParams))
  val dptw_port = IO(SRAMBundle(sramParams))
  val ext_irq = IO(Input(Bool()))
  val mtime_in = IO(Input(UInt(64.W)))
  val fence_i = IO(Output(Bool()))

  // modules
  val fe = Module(new FrontEnd)
  val be = Module(new BackEnd)

  // frontend -> instruction queue -> backend
  val instQueue = Queue(fe.io.out, entries = 4, flush = Some(be.io.flush))
  be.io.in <> instQueue

  // backend -> frontend control
  fe.io.redirect := be.io.redirect
  fe.io.flush := be.io.flush
  fe.flush_gpr := be.probe.bits.gpr

  // tie off icache (spike frontend fetches via DPI, not through cache)
  icache.req   := false.B
  icache.wen   := false.B
  icache.size  := 0.U
  icache.addr  := 0.U
  icache.wstrb := 0.U
  icache.wdata := 0.U

  // tie off iptw_port (spike doesn't use hardware PTW)
  iptw_port.req   := false.B
  iptw_port.wen   := false.B
  iptw_port.size  := 0.U
  iptw_port.addr  := 0.U
  iptw_port.wstrb := 0.U
  iptw_port.wdata := 0.U

  // bus
  be.dcache <> dcache
  be.perip <> perip
  be.ptw_port <> dptw_port

  // interrupts
  be.ext_irq := ext_irq
  be.mtime_in := mtime_in

  // fence
  fence_i := be.fence_i

  // probe
  val probe = IO(chiselTypeOf(be.probe))
  probe := be.probe
}
