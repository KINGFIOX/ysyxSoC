package ysyx.sim

import org.chipsalliance.cde.config.{Parameters, Config}
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.amba.axi4._
import freechips.rocketchip.amba.apb._
import freechips.rocketchip.system._
import freechips.rocketchip.subsystem._
import freechips.rocketchip.util.ElaborationArtefacts

import chisel3._
import chisel3.util._

// format: off
import ysyx.cpu.CPU
import ysyx.core.DebugBundle
import ysyx.device.{ APBUart16550, APBGPIO, APBKeyboard, APBVGA, ExternalPins }
import ysyx.sim.AXI4SDRAM
import ysyx.SoCConfig
import ysyx.amba._
// format: on

// format: off
class ysyxSoCASIC(implicit p: Parameters) extends LazyModule {
  val xbar = AXI4Xbar()
  val apbxbar = LazyModule(new APBFanout).node
  val cpu = LazyModule(new CPU)
  val luart = LazyModule( new APBUart16550( AddressSet.misaligned(SoCConfig.uartBase, SoCConfig.uartSize)))
  val lgpio = LazyModule( new APBGPIO(AddressSet.misaligned(SoCConfig.gpioBase, SoCConfig.gpioSize)))
  val lkeyboard = LazyModule( new APBKeyboard( AddressSet.misaligned(SoCConfig.keyboardBase, SoCConfig.keyboardSize)))
  val lvga = LazyModule( new APBVGA(AddressSet.misaligned(SoCConfig.vgaBase, SoCConfig.vgaSize)))
  val lflash = LazyModule( new APBFlash(AddressSet.misaligned(SoCConfig.xipFlashBase, SoCConfig.xipFlashSize)) )
  val lsdram_axi = LazyModule( new AXI4SDRAM(AddressSet.misaligned(SoCConfig.sdramBase, SoCConfig.sdramSize)) )
  List(luart.node, lgpio.node, lkeyboard.node, lvga.node, lflash.node).map(_ := apbxbar)
  apbxbar := AXI4ToAPB() := AXI4Buffer() := AXI4UserYanker(Some(1)) := AXI4Fragmenter() := xbar
  lsdram_axi.node := ysyx.amba.AXI4Delayer() := xbar

  xbar := cpu.masterNode

  override lazy val module = new Impl
  class Impl extends LazyModuleImp(this) {
    cpu.module.interrupt := false.B

    val probe = IO(chiselTypeOf(cpu.module.probe))
    val uart = IO(chiselTypeOf(luart.module.uart))
    val gpio = IO(chiselTypeOf(lgpio.module.gpio_bundle))
    val ps2 = IO(chiselTypeOf(lkeyboard.module.ps2_bundle))
    val vga = IO(chiselTypeOf(lvga.module.vga_bundle))
    probe := cpu.module.probe
    uart <> luart.module.uart
    gpio <> lgpio.module.gpio_bundle
    ps2 <> lkeyboard.module.ps2_bundle
    vga <> lvga.module.vga_bundle
  }
}
// format: on

class ysyxSoCFull(implicit p: Parameters) extends LazyModule {
  val asic = LazyModule(new ysyxSoCASIC)
  ElaborationArtefacts.add("graphml", graphML)

  override lazy val module = new Impl
  class Impl extends LazyModuleImp(this) {
    val masic = asic.module

    val externalPins = IO(new Bundle {
      val gpio = chiselTypeOf(masic.gpio)
      val ps2 = chiselTypeOf(masic.ps2)
      val vga = chiselTypeOf(masic.vga)
      val uart = chiselTypeOf(masic.uart)
    })
    externalPins.gpio <> masic.gpio
    externalPins.ps2 <> masic.ps2
    externalPins.vga <> masic.vga
    externalPins.uart <> masic.uart

    val probe = IO(chiselTypeOf(masic.probe))
    probe := masic.probe
  }
}

class NPCSoC
    extends FixedIORawModule(new Bundle {
      val clock = Input(Clock())
      val reset = Input(Bool())
      val externalPins = new ExternalPins
      val probe = Valid(new DebugBundle)
    })
    with ImplicitClock
    with ImplicitReset {
  override protected def implicitClock: Clock = io.clock
  override protected def implicitReset: Reset = io.reset

  implicit val config: Parameters = new Config(
    new DefaultConfig
  )

  val dut = LazyModule(new ysyxSoCFull)
  val mdut = Module(dut.module)
  mdut.externalPins <> io.externalPins
  io.probe := mdut.probe
}

object ElaborateNPCSoC extends App {
  val firtoolOptions = Array("--disable-annotation-unknown")
  _root_.circt.stage.ChiselStage.emitSystemVerilogFile(
    new NPCSoC,
    args,
    firtoolOptions
  )
}
