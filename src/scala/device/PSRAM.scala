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
    val wdata = Input(UInt(32.W))
    val rdata = Output(UInt(32.W))
  })
}

// eb: write (1, 4, 4)
// 38: read (1, 4, 4)
class psram extends RawModule {
  val io = IO(Flipped(new QSPIIO))
  val reset = io.ce_n.asAsyncReset
  val clock = io.sck.asClock
  val module = withClockAndReset(clock, reset) { Module(new Impl) }
  module.io.mosi := TriStateInBuf(io.dio, module.io.miso, module.io.mosiEn)
  class Impl extends Module with RequireAsyncReset {
    val io = IO(new Bundle{
      val miso = Output(UInt(4.W))
      val mosi = Input(UInt(4.W))
      val mosiEn = Output(Bool())
    })
    // TODO:
    io.miso := 0.U
    io.mosiEn := false.B
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

    val mpsram = Module(new psram_top_apb)
    mpsram.io.clock := clock
    mpsram.io.reset := reset
    mpsram.io.in <> in
    qspi_bundle <> mpsram.io.qspi
  }
}
