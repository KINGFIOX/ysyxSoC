package ysyx.sim

import org.chipsalliance.cde.config.Parameters
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.amba.axi4._
import freechips.rocketchip.amba.apb._

// format: off
import ysyx.cpu.CPU
import ysyx.device.{ APBUart16550, ChipLinkParam, APBGPIO, APBKeyboard, APBVGA, AXI4SDRAM }
import ysyx.SoCConfig
// format: on

class ysyxSoCSim(implicit p: Parameters) extends LazyModule {
  // format: off
  val xbar = AXI4Xbar()
  val apbxbar = LazyModule(new APBFanout).node
  val cpu = LazyModule(new CPU(idBits = ChipLinkParam.idBits))
  val luart = LazyModule( new APBUart16550( AddressSet.misaligned(SoCConfig.uartBase, SoCConfig.uartSize)))
  val lgpio = LazyModule( new APBGPIO(AddressSet.misaligned(SoCConfig.gpioBase, SoCConfig.gpioSize)))
  val lkeyboard = LazyModule( new APBKeyboard( AddressSet.misaligned(SoCConfig.keyboardBase, SoCConfig.keyboardSize)))
  val lvga = LazyModule( new APBVGA(AddressSet.misaligned(SoCConfig.vgaBase, SoCConfig.vgaSize)))
  val lflash = ???
  val lsdram_axi = LazyModule( new AXI4SDRAM( AddressSet.misaligned(SoCConfig.sdramBase, SoCConfig.sdramSize)))
  List(luart.node, lgpio.node, lkeyboard.node, lvga.node).map(_ := apbxbar)
  // format: on
  override lazy val module = new Impl
  class Impl extends LazyModuleImp(this) {}
}
