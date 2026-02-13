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

class APBPSRAM(address: Seq[AddressSet])(implicit p: Parameters)
    extends LazyModule {
  val node = APBSlaveNode(
    Seq(
      APBSlavePortParameters(
        Seq(
          APBSlaveParameters(
            address = address,
            executable = true,
            supportsRead = true,
            supportsWrite = true
          )
        ),
        beatBytes = 4
      )
    )
  )

  lazy val module = new Impl
  class Impl extends LazyModuleImp(this) {
    val (in, _) = node.in(0)
    val qspi_bundle = IO(new QSPIIO)

    val mpsram = Module(
      new psram_top_apb
    ) // instantiate the psram_top_apb module ( including qspi controller )
    mpsram.io.in <> in
    qspi_bundle <> mpsram.io.qspi
  }
}

// ============================================================================
// QSPI Controller (这个其实是: apb to qspi, 针对 psram)
// ============================================================================

// cnt:       2  1  0  2  1  0  2  1  0  2
// clkOut:    0  0  0  1  1  1  0  0  0  1
// posEdge:   0  0  1  0  0  0  0  0  1  0
// negEdge:   0  0  0  0  0  1  0  0  0  0
class qspi_clgen extends Module {
  val io = IO(new Bundle {
    val go = Input(Bool()) // from SPI CTRL register
    val enable = Input(Bool()) // transfer in progress, the handshaking signal from spi_shift module
    val lastClk = Input(Bool()) // from spi_shift module, mark this is the last clock
    val divider = Input(UInt(16.W)) // from SPI CTRL register
    val clkOut = Output(Bool()) // sck
    val posEdge = Output(Bool())
    val negEdge = Output(Bool())
  })

  val cnt = RegInit("hffff".U(16.W)) // init with 0xffff
  when(!io.enable || cntZero) {
    cnt := io.divider
  }.otherwise {
    cnt := cnt - 1.U
  }
  val clkOut = RegInit(false.B)
  io.clkOut := clkOut

  val cntZero = cnt === 0.U
  val cntOne = cnt === 1.U

  // SCK gen: when cntZero and lastClk asserted, only 1 -> 0 transition is allowed
  when(io.enable && cntZero && (!io.lastClk || clkOut)) {
    clkOut := ~clkOut
  }

  val divZero = io.divider === 0.U
  io.posEdge := RegNext(
    (io.enable && !clkOut && cntOne) || // cntOnt implicitly indicate !divZero
      (divZero && clkOut) || // especially, divider=0, phase diff 180 degree
      (divZero && io.go && !io.enable) // make first posedge
    ,
    false.B
  )
  io.negEdge := RegNext(
    (io.enable && clkOut && cntOne) ||
      (divZero && !clkOut && io.enable)
    ,
    false.B
  )
}

class psram_top_apb extends Module {
  val io = IO(new Bundle {
    val in =
      Flipped(new APBBundle(APBBundleParameters(addrBits = 32, dataBits = 32)))
    val qspi = new QSPIIO
  })
  val miso = WireDefault(0.U(4.W)) // TODO:
  val misoEn = WireDefault(false.B)
  val mosi = TriStateInBuf(io.qspi.dio, miso, misoEn)

  // io.in.psel -> aligned <=> ! io.in.psel || aligned, 蕴含恒等式
  assert(!(io.in.psel) || !(io.in.paddr & "b011".U), "unaligned address")

  val sck = RegInit(false.B)
  val wdata = MuxLookup(io.in.pstrb, io.in.pwdata)(
    Seq(
      "b0001".U -> io.in.pwdata(7, 0),
      "b0010".U -> io.in.pwdata(15, 8),
      "b0100".U -> io.in.pwdata(23, 16),
      "b1000".U -> io.in.pwdata(31, 24),
      "b0011".U -> io.in.pwdata(15, 0),
      "b1100".U -> io.in.pwdata(31, 16),
      "b1111".U -> io.in.pwdata(31, 0)
    )
  )
  val addr = io.in.paddr + Mux(
    io.in.psel && io.in.pwrite,
    MuxLookup(io.in.pstrb, 0.U)(
      Seq(
        "b0001".U -> 0.U,
        "b0010".U -> 1.U,
        "b0100".U -> 2.U,
        "b1000".U -> 3.U,
        "b0011".U -> 0.U,
        "b1100".U -> 2.U,
        "b1111".U -> 0.U
      )
    ),
    0.U
  )
  val wlens = MuxLookup(io.in.pstrb, 1.U)(
    Seq( // 写入的长度
      "b0001".U -> 1.U,
      "b0010".U -> 1.U,
      "b0100".U -> 1.U,
      "b1000".U -> 1.U,
      "b0011".U -> 2.U,
      "b1100".U -> 2.U,
      "b1111".U -> 4.U
    )
  )

}
