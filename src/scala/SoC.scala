package ysyx

import chisel3._
import chisel3.util._

import freechips.rocketchip.diplomacy._
import org.chipsalliance.cde.config.Parameters
import freechips.rocketchip.subsystem._
import freechips.rocketchip.util._
import freechips.rocketchip.amba.axi4._
import freechips.rocketchip.amba.apb._
import freechips.rocketchip.system.SimAXIMem

import ysyx.core.DebugBundle

object AXI4SlaveNodeGenerator {
  def apply(params: Option[MasterPortParams], address: Seq[AddressSet])(implicit valName: ValName) =
    AXI4SlaveNode(params.map(p => AXI4SlavePortParameters(
        slaves = Seq(AXI4SlaveParameters(
          address       = address,
          executable    = p.executable,
          supportsWrite = TransferSizes(1, p.maxXferBytes),
          supportsRead  = TransferSizes(1, p.maxXferBytes))),
        beatBytes = p.beatBytes
      )).toSeq)
}

class ysyxSoCASIC(implicit p: Parameters) extends LazyModule {
  val xbar = AXI4Xbar()
  val xbar2 = AXI4Xbar()
  val apbxbar = LazyModule(new APBFanout).node
  val cpu = LazyModule(new CPU(idBits = ChipLinkParam.idBits))
  val chipMaster = if (Config.hasChipLink) Some(LazyModule(new ChipLinkMaster)) else None
  val chiplinkNode = if (Config.hasChipLink) Some(AXI4SlaveNodeGenerator(p(ExtBus), ChipLinkParam.allSpace)) else None

  val luart = LazyModule(new APBUart16550(AddressSet.misaligned(SoCConfig.uartBase, SoCConfig.uartSize)))
  val lgpio = LazyModule(new APBGPIO(AddressSet.misaligned(SoCConfig.gpioBase, SoCConfig.gpioSize)))
  val lkeyboard = LazyModule(new APBKeyboard(AddressSet.misaligned(SoCConfig.keyboardBase, SoCConfig.keyboardSize)))
  val lvga = LazyModule(new APBVGA(AddressSet.misaligned(SoCConfig.vgaBase, SoCConfig.vgaSize)))
  // SPI controller (generic, device-agnostic)
  val lspi = LazyModule(new APBSPI(AddressSet.misaligned(SoCConfig.spiCtrlBase, SoCConfig.spiCtrlSize)))
  // XIP Flash controller (flash-specific, knows the read protocol)
  val lxipflash = LazyModule(new APBXIPFlash(AddressSet.misaligned(SoCConfig.xipFlashBase, SoCConfig.xipFlashSize)))
  val lpsram = LazyModule(new APBPSRAM(AddressSet.misaligned(SoCConfig.psramBase, SoCConfig.psramSize)))
  val lmrom = LazyModule(new AXI4MROM(AddressSet.misaligned(SoCConfig.mromBase, SoCConfig.mromSize)))
  val sramNode = AXI4RAM(AddressSet.misaligned(SoCConfig.sramBase, SoCConfig.sramSize).head, false, true, 4, None, Nil, false)

  val sdramAddressSet = AddressSet.misaligned(SoCConfig.sdramBase, SoCConfig.sdramSize)
  val lsdram_apb = if (!Config.sdramUseAXI) Some(LazyModule(new APBSDRAM (sdramAddressSet))) else None
  val lsdram_axi = if ( Config.sdramUseAXI) Some(LazyModule(new AXI4SDRAM(sdramAddressSet))) else None

  // APB devices connected to APB crossbar
  // Note: lxipflash is the XIP Flash controller (separate from SPI controller)
  List(lspi.node, lxipflash.node, luart.node, lpsram.node, lgpio.node, lkeyboard.node, lvga.node).map(_ := apbxbar)
  List(apbxbar := APBDelayer() := AXI4ToAPB() := AXI4Buffer(), lmrom.node, sramNode).map(_ := xbar2)
  xbar2 := AXI4UserYanker(Some(1)) := AXI4Fragmenter() := xbar
  if (Config.sdramUseAXI) lsdram_axi.get.node := ysyx.AXI4Delayer() := xbar
  else                    lsdram_apb.get.node := apbxbar
  if (Config.hasChipLink) chiplinkNode.get := xbar
  xbar := cpu.masterNode

  override lazy val module = new Impl
  class Impl extends LazyModuleImp(this) with DontTouch {
    // generate delayed reset for cpu, since chiplink should finish reset
    // to initialize some async modules before accept any requests from cpu
    cpu.module.reset := SynchronizerShiftReg(reset.asBool, 10) || reset.asBool

    val fpga_io = if (Config.hasChipLink) Some(IO(chiselTypeOf(chipMaster.get.module.fpga_io))) else None
    if (Config.hasChipLink) {
      // connect chiplink slave interface to crossbar
      (chipMaster.get.slave zip chiplinkNode.get.in) foreach { case (io, (bundle, _)) => io <> bundle }
      // connect chiplink dma interface to cpu
      cpu.module.slave <> chipMaster.get.master_mem(0)
      // expose chiplink fpga I/O interface as ports
      fpga_io.get <> chipMaster.get.module.fpga_io
    } else {
      cpu.module.slave := DontCare
    }

    // connect interrupt signal to cpu
    val intr_from_chipSlave = IO(Input(Bool()))
    cpu.module.interrupt := intr_from_chipSlave

    // expose step and debug signals for simulation
    val step = IO(Input(Bool()))
    val debug = IO(Output(new DebugBundle))
    cpu.module.step := step
    debug := cpu.module.debug

    val sdramBundle = if (Config.sdramUseAXI) lsdram_axi.get.module.sdram_bundle
                      else                    lsdram_apb.get.module.sdram_bundle

    // expose slave I/O interface as ports
    val spi = IO(chiselTypeOf(lspi.module.spi_bundle))
    val uart = IO(chiselTypeOf(luart.module.uart))
    val psram = IO(chiselTypeOf(lpsram.module.qspi_bundle))
    val sdram = IO(chiselTypeOf(sdramBundle))
    val gpio = IO(chiselTypeOf(lgpio.module.gpio_bundle))
    val ps2 = IO(chiselTypeOf(lkeyboard.module.ps2_bundle))
    val vga = IO(chiselTypeOf(lvga.module.vga_bundle))
    uart <> luart.module.uart
    spi <> lspi.module.spi_bundle
    // Connect XIP Flash controller's APB master to SPI controller's XIP slave port
    lspi.module.xip_apb <> lxipflash.module.spi_apb
    psram <> lpsram.module.qspi_bundle
    sdram <> sdramBundle
    gpio <> lgpio.module.gpio_bundle
    ps2 <> lkeyboard.module.ps2_bundle
    vga <> lvga.module.vga_bundle
  }
}

class ysyxSoCFPGA(implicit p: Parameters) extends ChipLinkSlave


class ysyxSoCFull(implicit p: Parameters) extends LazyModule {
  val asic = LazyModule(new ysyxSoCASIC)
  ElaborationArtefacts.add("graphml", graphML)

  override lazy val module = new Impl
  class Impl extends LazyModuleImp(this) with DontTouch {
    val masic = asic.module // module asic

    if (Config.hasChipLink) {
      val fpga = LazyModule(new ysyxSoCFPGA)
      val mfpga = Module(fpga.module)
      masic.dontTouchPorts()

      masic.fpga_io.get.b2c <> mfpga.fpga_io.c2b
      mfpga.fpga_io.b2c <> masic.fpga_io.get.c2b

      (fpga.master_mem zip fpga.axi4MasterMemNode.in).map { case (io, (_, edge)) =>
        val mem = LazyModule(new SimAXIMem(edge, base = ChipLinkParam.mem.base, size = ChipLinkParam.mem.mask + 1))
        Module(mem.module)
        mem.io_axi4.head <> io
      }

      fpga.master_mmio.map(_ := DontCare)
      fpga.slave.map(_ := DontCare)
    }

    masic.intr_from_chipSlave := false.B

    val flash = Module(new flash)
    flash.io <> masic.spi
    flash.io.ss := masic.spi.ss(0)

    val bitrev = Module(new bitrev)
    bitrev.io <> masic.spi
    bitrev.io.ss := masic.spi.ss(7)
    masic.spi.miso := List(bitrev.io, flash.io).map(_.miso).reduce(_ && _)

    val psram = Module(new psram)
    psram.io <> masic.psram
    val sdram = Module(new sdram)
    sdram.io <> masic.sdram

    val externalPins = IO(new Bundle{
      val gpio = chiselTypeOf(masic.gpio)
      val ps2 = chiselTypeOf(masic.ps2)
      val vga = chiselTypeOf(masic.vga)
      val uart = chiselTypeOf(masic.uart)
    })
    externalPins.gpio <> masic.gpio
    externalPins.ps2 <> masic.ps2
    externalPins.vga <> masic.vga
    externalPins.uart <> masic.uart

    // expose step and debug for simulation
    val step = IO(Input(Bool()))
    val debug = IO(Output(new DebugBundle))
    masic.step := step
    debug := masic.debug
  }
}
