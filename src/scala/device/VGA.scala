package ysyx

import chisel3._
import chisel3.util._

import freechips.rocketchip.amba.apb._
import org.chipsalliance.cde.config.Parameters
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.util._

class VGAIO extends Bundle {
  val r = Output(UInt(8.W))
  val g = Output(UInt(8.W))
  val b = Output(UInt(8.W))
  val hsync = Output(Bool())
  val vsync = Output(Bool())
  val valid = Output(Bool())
}

class VGACtrlIO extends Bundle {
  val in = Flipped(
    new APBBundle(APBBundleParameters(addrBits = 32, dataBits = 32))
  )
  val vga = new VGAIO
}

class VGAMemIO extends Bundle {
  val hAddr = Input(UInt(10.W))
  val vAddr = Input(UInt(10.W))
  val rdata = Output(UInt(24.W))
}

case class VGATimingParams(
    hFrontPorch: Int = 96,
    hActive: Int = 144,
    hBackPorch: Int = 784,
    hTotal: Int = 800,
    vFrontPorch: Int = 2,
    vActive: Int = 35,
    vBackPorch: Int = 515,
    vTotal: Int = 525
)

class VGACore(params: VGATimingParams = VGATimingParams()) extends Module {
  val vga = IO(new VGAIO)
  val mem = IO(Flipped(new VGAMemIO))

  private val xCntQ = RegInit(1.U(10.W))
  private val yCntQ = RegInit(1.U(10.W))

  when(xCntQ === params.hTotal.U) {
    xCntQ := 1.U
    when(yCntQ === params.vTotal.U) {
      yCntQ := 1.U
    }.otherwise {
      yCntQ := yCntQ + 1.U
    }
  }.otherwise {
    xCntQ := xCntQ + 1.U
  }

  vga.hsync := xCntQ > params.hFrontPorch.U
  vga.vsync := yCntQ > params.vFrontPorch.U

  val hValidW = (xCntQ > params.hActive.U) & (xCntQ <= params.hBackPorch.U)
  val vValidW = (yCntQ > params.vActive.U) & (yCntQ <= params.vBackPorch.U)
  vga.valid := hValidW & vValidW

  private val hAddrW = Mux(hValidW, xCntQ - (params.hActive + 1).U, 0.U)
  private val vAddrW = Mux(vValidW, yCntQ - (params.vActive + 1).U, 0.U)

  // mem
  private val rdataW = mem.rdata
  mem.hAddr := hAddrW
  mem.vAddr := vAddrW

  vga.r := rdataW(23, 16)
  vga.g := rdataW(15, 8)
  vga.b := rdataW(7, 0)
}

class vga_top_apb extends Module {
  val io = IO(new VGACtrlIO)

  // mem
  private val mem = Mem(524288, UInt(24.W))
  private val paddrQ = RegInit(0.U(19.W)) // log2(524288) = 19

  // states
  object State extends ChiselEnum {
    val idle, ready = Value
  }
  private val stateQ = RegInit(State.idle)

  // state machine
  switch(stateQ) {

    is(State.idle) {
      when(io.in.psel) {
        stateQ := State.ready
        when(io.in.pwrite) {
          mem.write(io.in.paddr, io.in.pwdata)
        }.otherwise {
          paddrQ := io.in.paddr
        }
      }
    }

    is(State.ready) {
      when(io.in.penable) {
        stateQ := State.idle
      }
    }

  }

  // apb
  io.in.pready := stateQ === State.ready
  io.in.prdata := mem.read(paddrQ)
  io.in.pslverr := false.B

  // vga
  private val core = Module(new VGACore)
  core.vga <> io.vga
  core.mem.rdata := mem.read(core.mem.hAddr ## core.mem.vAddr)

}

class APBVGA(address: Seq[AddressSet])(implicit p: Parameters)
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
    val vga_bundle = IO(new VGAIO)

    val mvga = Module(new vga_top_apb)
    mvga.io.in <> in
    vga_bundle <> mvga.io.vga
  }
}
