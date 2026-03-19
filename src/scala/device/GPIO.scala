package ysyx.device

import chisel3._
import chisel3.util._

import freechips.rocketchip.amba.apb._
import org.chipsalliance.cde.config.Parameters
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.util._

class GPIOIO extends Bundle {
  val out = Output(UInt(16.W)) // led 灯
  val in = Input(UInt(16.W)) // 拨码开关
  val seg = Output(Vec(8, UInt(8.W))) // 数码管
}

class GPIOCtrlIO extends Bundle {
  val clock = Input(Clock())
  val reset = Input(Reset())
  val in = Flipped(new APBBundle(APBBundleParameters(addrBits = 32, dataBits = 32)))
  val gpio = new GPIOIO
}

// IMPORTANT:
// leds: 0, 1
// switches: 4, 5
// segs: 8, 9, 10, 11, 12, 13, 14, 15
// which means: addr should be aligned to 4 bytes
class gpio_top_apb extends Module {
  val io = IO(new GPIOCtrlIO)

  // --- mmio register ---
  val segsQ = RegInit(VecInit(Seq.fill(8)("hff".U(8.W))))
  val ledsQ = RegInit(VecInit(Seq.fill(2)("hff".U(8.W))))
  
  // --- outputs ---
  io.gpio.out := ledsQ.asUInt
  io.gpio.seg := segsQ
  
  // --- alias ---
  val addrW = io.in.paddr(3, 0)
  val pstrbW = io.in.pstrb
  val wdataW = io.in.pwdata

  // --- state machine ---
  object State extends ChiselEnum {
    val idle, access, ready = Value
  }
  val stateQ = RegInit(State.idle)

  // --- apb signals ---
  val rdataQ = RegInit(0.U(32.W))
  io.in.prdata := rdataQ
  io.in.pslverr := false.B
  io.in.pready := (stateQ === State.ready)

  switch(stateQ) {

    is(State.idle) {
      when(io.in.psel) {
        stateQ := State.access
      }
    }

    is(State.access) {
      when(io.in.pwrite) { // write
        when( addrW === 0.U ) { // leds
          when( pstrbW(0) ) { ledsQ(0) := wdataW(7, 0) }
          when( pstrbW(1) ) { ledsQ(1) := wdataW(15, 8) }
        } .elsewhen( addrW === 8.U ) { // segs
          when( pstrbW(0) ) { segsQ(0) := wdataW(7, 0) }
          when( pstrbW(1) ) { segsQ(1) := wdataW(15, 8) }
          when( pstrbW(2) ) { segsQ(2) := wdataW(23, 16) }
          when( pstrbW(3) ) { segsQ(3) := wdataW(31, 24) }
        } .elsewhen( addrW === 12.U ) { // segs
          when( pstrbW(0) ) { segsQ(4) := wdataW(7, 0) }
          when( pstrbW(1) ) { segsQ(5) := wdataW(15, 8) }
          when( pstrbW(2) ) { segsQ(6) := wdataW(23, 16) }
          when( pstrbW(3) ) { segsQ(7) := wdataW(31, 24) }
        }
        // write, ignore switches
      } .otherwise { // read
        when( addrW === 0.U ) { // leds
          rdataQ := ledsQ.asUInt
        } .elsewhen( addrW === 4.U ) { // switches
          rdataQ := io.gpio.in
        } .elsewhen( addrW === 8.U ) { // segs
          rdataQ := Cat(segsQ(3), segsQ(2), segsQ(1), segsQ(0))
        } .elsewhen( addrW === 12.U ) { // segs
          rdataQ := Cat(segsQ(7), segsQ(6), segsQ(5), segsQ(4))
        }
      }

      stateQ := State.ready
    }

    is(State.ready) {
      when(io.in.penable) {
        stateQ := State.idle
      }
    }

  }

}

class APBGPIO(address: Seq[AddressSet])(implicit p: Parameters) extends LazyModule {
  val node = APBSlaveNode(Seq(APBSlavePortParameters(
    Seq(APBSlaveParameters(
      address       = address,
      executable    = true,
      supportsRead  = true,
      supportsWrite = true)),
    beatBytes  = 4)))

  lazy val module = new Impl
  class Impl extends LazyModuleImp(this) {
    val (in, _) = node.in(0)
    val gpio_bundle = IO(new GPIOIO)

    val mgpio = Module(new gpio_top_apb)
    mgpio.io.clock := clock
    mgpio.io.reset := reset
    mgpio.io.in <> in
    gpio_bundle <> mgpio.io.gpio
  }
}
