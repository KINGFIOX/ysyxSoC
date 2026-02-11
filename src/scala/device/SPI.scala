package ysyx

import chisel3._
import chisel3.util._

import freechips.rocketchip.amba.apb._
import org.chipsalliance.cde.config.Parameters
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.util._


// ============================================================================
// SPI Physical Interface
// ============================================================================

class SPIIO(val ssWidth: Int = 8) extends Bundle {
  val sck = Output(Bool())
  val ss = Output(UInt(ssWidth.W))
  val mosi = Output(Bool())
  val miso = Input(Bool())
}

class spi_top_apb extends BlackBox {
  val io = IO(new Bundle {
    val clock = Input(Clock())
    val reset = Input(Reset())
    val in = Flipped(new APBBundle(APBBundleParameters(addrBits = 32, dataBits = 32)))
    val spi = new SPIIO
    val spi_irq_out = Output(Bool())
  })
}


/** Generic SPI Controller (APB slave)
  *
  * This is a pure SPI controller without any device-specific knowledge.
  * It wraps the spi_top_apb BlackBox and provides:
  *   - APB slave interface for register access (via Diplomacy node)
  *   - SPI physical interface (sck, ss, mosi, miso)
  *
  * For multi-master access (e.g., XIP controller + CPU), use an external
  * APBArbiter to arbitrate between multiple masters before connecting to this node.
  */
class APBSPI(address: Seq[AddressSet])(implicit p: Parameters) extends LazyModule {
  val node = APBSlaveNode(Seq(
    APBSlavePortParameters(Seq(
      APBSlaveParameters(
        address = address,
        executable = true,
        supportsRead = true,
        supportsWrite = true
      )),
      beatBytes = 4
    )))

  lazy val module = new Impl
  class Impl extends LazyModuleImp(this) {
    val (apbIn, _) = node.in(0)
    val spi_bundle = IO(new SPIIO)

    val mspi = Module(new spi_top_apb)
    mspi.io.clock := clock
    mspi.io.reset := reset
    spi_bundle <> mspi.io.spi

    // Direct connection - no internal arbitration
    mspi.io.in <> apbIn
  }
}


/** Convenience class for SPI Flash XIP (backward compatibility)
  * Uses the generic APBXIPController from amba package with SPIFlash config.
  */
class APBXIPFlash(address: Seq[AddressSet])(implicit p: Parameters)
    extends APBXIPController(address, XIPConfig.SPIFlash)
