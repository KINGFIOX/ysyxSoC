package ysyx

import chisel3._
import chisel3.util._
import chisel3.experimental.Analog

import freechips.rocketchip.amba.apb._
import org.chipsalliance.cde.config.Parameters
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.util._

class QSPIIO extends Bundle {
  val sck = Output(Bool())
  val ce_n = Output(Bool())
  val dio = Analog(4.W)
}

class psram_top_apb extends BlackBox {
  val io = IO(new Bundle {
    val clock = Input(Clock())
    val reset = Input(Reset())
    val in = Flipped(new APBBundle(APBBundleParameters(addrBits = 32, dataBits = 32)))
    val qspi = new QSPIIO
  })
}

class psram_cmd extends BlackBox {
  val io = IO(new Bundle {
    val clock = Input(Clock())
    val valid = Input(Bool())
    val cmd = Input(UInt(8.W))
    val addr = Input(UInt(32.W))
    val wdata = Input(UInt(8.W))
    val rdata = Output(UInt(8.W))
  })
}

// eb: write (1, 4, 4)
// 38: read (1, 4, 4)
class psram extends RawModule {
  val io = IO(Flipped(new QSPIIO))
  val reset = io.ce_n.asAsyncReset
  val clock = io.sck.asClock
  val module = withClockAndReset(clock, reset) { Module(new Impl) }
  module.io.mosi := TriStateInBuf(io.dio, module.io.miso, module.io.misoEn)
  class Impl extends Module with RequireAsyncReset {
    val io = IO(new Bundle{
      val miso = Output(UInt(4.W))
      val mosi = Input(UInt(4.W))
      val misoEn = Output(Bool())
    })
    object State extends ChiselEnum {
      val cmd, addr, wait_read, data = Value
    }
    val counter = RegInit(0.U(5.W))
    val state = RegInit(State.cmd)
    val cmd = RegInit(0.U(8.W))
    val addr = RegInit(0.U(32.W));
    val base = RegInit(0.U(24.W)); val offset = RegInit(0.U(10.W)) // wrapping
    val wdataH = RegInit(0.U(4.W))
    val u0_psram_cmd = Module(new psram_cmd)
    u0_psram_cmd.io.clock := this.clock
    u0_psram_cmd.io.valid := false.B
    u0_psram_cmd.io.cmd := cmd
    u0_psram_cmd.io.addr := Cat( base, offset )
    u0_psram_cmd.io.wdata := Cat( wdataH, io.mosi )
    val rdata = u0_psram_cmd.io.rdata

    io.miso := 0.U
    io.misoEn := false.B // default

    switch(state) {
      is(State.cmd) {
        counter := counter + 1.U
        cmd := Cat( cmd(6, 0), io.mosi(0) )
        when(counter === 7.U) {
          counter := 0.U
          state := State.addr
        }
      }
      is(State.addr) {
        counter := counter + 1.U
        val next_addr = Cat( 0.U(8.W), addr(19, 0), io.mosi )
        addr := next_addr; base := next_addr(23, 10); offset := next_addr(9, 0)
        when( counter === 5.U ) {
          counter := 0.U
          assert( cmd === "heb".U || cmd === "h38".U, cf"Assert failed: Unsupportted command `${cmd}%x`" )
          when( cmd === "heb".U ) {
            state := State.wait_read
          } .elsewhen( cmd === "h38".U ) {
            state := State.data
          }
        }
      }
      is(State.wait_read) {
        counter := counter + 1.U
        when( counter === 5.U ) {
          counter := 0.U
          u0_psram_cmd.io.valid := true.B // pulse
          state := State.data
        }
      }
      is(State.data) {
        assert( cmd === "heb".U || cmd === "h38".U, "impossible" )
        when( cmd === "heb".U ) { // read
          io.misoEn := true.B
          when( counter === 0.U ) {
            counter := 1.U
            io.miso := rdata(7, 4)
          } .otherwise {  // counter === 1
            counter := 0.U
            io.miso := rdata(3, 0)
            u0_psram_cmd.io.valid := true.B
            val next_offset = offset + 1.U
            u0_psram_cmd.io.addr := Cat( base, next_offset )
            offset := next_offset
          }
        } .elsewhen( cmd === "h38".U ) { // write
          when( counter === 0.U ) {
            counter := 1.U
            wdataH := io.mosi
          } .otherwise {  // counter === 1
            counter := 0.U
            offset := offset + 1.U
            u0_psram_cmd.io.valid := true.B
          }
        }
      }
    }
  }
}

class APBPSRAM(address: Seq[AddressSet])(implicit p: Parameters) extends LazyModule {
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
    val qspi_bundle = IO(new QSPIIO)

    val mpsram = Module(new psram_top_apb) // instantiate the psram_top_apb module ( including qspi controller )
    mpsram.io.clock := clock
    mpsram.io.reset := reset
    mpsram.io.in <> in
    qspi_bundle <> mpsram.io.qspi
  }
}
