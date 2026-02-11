package ysyx

import chisel3._
import chisel3.util._

import freechips.rocketchip.amba.apb._
import org.chipsalliance.cde.config.Parameters
import freechips.rocketchip.diplomacy._

// ============================================================================
// XIP Protocol Configuration
// ============================================================================

/** XIP Protocol Configuration
  *
  * Describes how to perform XIP operations for a specific device. Different
  * devices (SPI Flash, QSPI PSRAM, etc.) have different:
  *   - Read/Write commands and address formats
  *   - Number of TX/RX registers used
  *   - CHAR_LEN (total bits transferred)
  *   - Byte ordering
  *
  * @param readCmd
  *   Read command byte (e.g., 0x03 for SPI Flash, 0xEB for QSPI)
  * @param writeCmd
  *   Write command byte, None for read-only devices (e.g., Some(0x38) for QSPI
  *   PSRAM)
  * @param addrBits
  *   Address width in bits (e.g., 24 for SPI Flash)
  * @param dummyBits
  *   Dummy bits between address and data for read (e.g., 0 for 0x03, 24 for
  *   0xEB)
  * @param dataBits
  *   Data width in bits (typically 32)
  * @param ssIndex
  *   Slave Select bit index (0-7)
  * @param swapBytes
  *   Whether to byte-swap the data (MSB-first to little-endian)
  */
case class XIPConfig(
    readCmd: Int,
    writeCmd: Option[Int] = None,
    addrBits: Int,
    dummyBits: Int = 0,
    dataBits: Int = 32,
    ssIndex: Int = 0,
    swapBytes: Boolean = true
) {
  // Whether device supports write (derived from writeCmd)
  def supportsWrite: Boolean = writeCmd.isDefined
  // Total bits = command(8) + address + dummy + data
  def charLen: Int = 8 + addrBits + dummyBits + dataBits

  // CTRL register value: ASS(bit13) | GO(bit8) | CHAR_LEN(bits 6:0)
  def ctrlValue: Int = (1 << 13) | (1 << 8) | charLen

  // SS register value
  def ssValue: Int = 1 << ssIndex

  // Calculate which TX registers are needed based on total TX bits
  // TX bits = command + address + dummy (data placeholder is in lower bits)
  def txBits: Int = 8 + addrBits + dummyBits + dataBits
  def numTxRegs: Int = (txBits + 31) / 32 // Round up to 32-bit registers

  // Calculate which RX registers contain valid data
  // RX data is in the lower bits after transmission
  def numRxRegs: Int = (dataBits + 31) / 32
}

object XIPConfig {

  /** SPI Flash with 0x03 read command (read-only) Protocol: 8-bit cmd + 24-bit
    * addr + 32-bit data = 64 bits TX1 = cmd | addr (32 bits), TX0 = 0
    * (placeholder) RX0 = received data
    */
  val SPIFlash = XIPConfig(
    readCmd = 0x03,
    writeCmd =
      None, // Flash requires special erase/program sequence, no direct write
    addrBits = 24,
    dummyBits = 0,
    dataBits = 32,
    ssIndex = 0,
    swapBytes = true
  )

}

// ============================================================================
// Generic XIP Controller
// ============================================================================

/** Generic XIP Controller
  *
  * A configurable XIP (eXecute In Place) controller that can work with
  * different SPI/QSPI devices by using XIPConfig to describe the protocol.
  *
  * Architecture:
  *   - APB Slave (via Diplomacy): receives XIP read requests from CPU
  *   - APB Master (direct bundle): sends SPI register accesses to SPI/QSPI
  *     controller
  *
  * The controller uses a state machine to:
  *   1. Select the device (write SS register)
  *   2. Write TX registers with command, address, and dummy bits
  *   3. Start transfer (write CTRL register with GO bit)
  *   4. Poll CTRL until GO bit clears
  *   5. Read RX registers to get data
  *   6. Deselect the device
  *   7. Return data to CPU
  *
  * @param address
  *   XIP address range (where CPU sees the device content)
  * @param config
  *   XIP protocol configuration for the target device
  */
class APBXIPController(address: Seq[AddressSet], config: XIPConfig)(implicit
    p: Parameters
) extends LazyModule {
  val node = APBSlaveNode(
    Seq(
      APBSlavePortParameters(
        Seq(
          APBSlaveParameters(
            address = address,
            executable = true, // May contain executable code
            supportsRead = true,
            supportsWrite = config.supportsWrite
          )
        ),
        beatBytes = 4
      )
    )
  )

  lazy val module = new Impl
  class Impl extends LazyModuleImp(this) {
    val (in, _) = node.in(0)
    // APB Master port to SPI/QSPI controller
    val spi_apb = IO(
      new APBBundle(APBBundleParameters(addrBits = 32, dataBits = 32))
    )

    // SPI register offsets (standard for both SPI and QSPI controllers)
    val ADDR_TX0 = 0x00.U(32.W)
    val ADDR_TX1 = 0x04.U(32.W)
    val ADDR_TX2 = 0x08.U(32.W)
    val ADDR_TX3 = 0x0c.U(32.W)
    val ADDR_CTRL = 0x10.U(32.W)
    val ADDR_SS = 0x18.U(32.W)
    val ADDR_RX0 = 0x00.U(32.W)
    val ADDR_RX1 = 0x04.U(32.W)
    val ADDR_RX2 = 0x08.U(32.W)
    val ADDR_RX3 = 0x0c.U(32.W)

    // State machine
    // Dynamic states based on numTxRegs and numRxRegs
    object State extends ChiselEnum {
      val Idle, WriteError, SS_Setup, SS_Access, TX3_Setup, TX3_Access,
          TX2_Setup, TX2_Access, TX1_Setup, TX1_Access, TX0_Setup, TX0_Access,
          Ctrl_Setup, Ctrl_Access, Poll_Setup, Poll_Access, RX0_Setup,
          RX0_Access, RX1_Setup, RX1_Access, RX2_Setup, RX2_Access, RX3_Setup,
          RX3_Access, DeSSel_Setup, DeSSel_Access, Respond = Value
    }
    val state = RegInit(State.Idle)

    // Captured address, write data, and received data
    val flashAddr = Reg(UInt(config.addrBits.W))
    val isWrite = Reg(Bool())
    val writeData = Reg(UInt(config.dataBits.W))
    val rxData = Reg(Vec(config.numRxRegs, UInt(32.W)))

    // Build TX data based on operation type:
    // Read:  [readCmd  | addr | dummy | 0 (placeholder)]
    // Write: [writeCmd | addr | data] (no dummy for write typically)
    val txBits = config.charLen
    val writeCmdByte = config.writeCmd.getOrElse(0).U(8.W)
    val cmdByte = Mux(isWrite, writeCmdByte, config.readCmd.U(8.W))

    // For write: swap bytes if needed before sending
    val writeDataSwapped = if (config.swapBytes) {
      Cat(
        writeData(7, 0),
        writeData(15, 8),
        writeData(23, 16),
        writeData(31, 24)
      )
    } else {
      writeData
    }

    // TX data construction
    // Read:  cmd(8) + addr(addrBits) + dummy(dummyBits) + 0(dataBits)
    // Write: cmd(8) + addr(addrBits) + data(dataBits) (typically no dummy for write)
    val txDataRead = Cat(
      config.readCmd.U(8.W),
      flashAddr,
      0.U(config.dummyBits.W),
      0.U(config.dataBits.W)
    )
    // For write, we assume no dummy bits (common for PSRAM write)
    // Write CHAR_LEN = 8 + addrBits + dataBits
    val writeCharLen = 8 + config.addrBits + config.dataBits
    val txDataWrite = Cat(
      writeCmdByte,
      flashAddr,
      writeDataSwapped
    )

    val cmdAddrDummy = Mux(isWrite, txDataWrite, txDataRead)

    // Split into 32-bit chunks for TX registers
    val txRegs = Wire(Vec(4, UInt(32.W)))
    val effectiveTxBits = Mux(isWrite, writeCharLen.U, txBits.U)
    for (i <- 0 until 4) {
      val highBit = txBits - 1 - i * 32
      val lowBit = math.max(0, txBits - (i + 1) * 32)
      if (highBit >= 0 && highBit >= lowBit) {
        txRegs(i) := cmdAddrDummy(highBit, lowBit)
      } else {
        txRegs(i) := 0.U
      }
    }

    // Configuration values
    val ssValue = config.ssValue.U(32.W)
    val readCtrlValue = config.ctrlValue.U(32.W)
    // Write CTRL: ASS | GO | writeCharLen
    val writeCtrlValue = ((1 << 13) | (1 << 8) | writeCharLen).U(32.W)
    val ctrlValue = Mux(isWrite, writeCtrlValue, readCtrlValue)

    // APB transaction helper
    def apbSetup(addr: UInt, write: Boolean, data: UInt = 0.U): Unit = {
      spi_apb.psel := true.B
      spi_apb.penable := false.B
      spi_apb.paddr := addr
      spi_apb.pwrite := write.B
      spi_apb.pwdata := data
      spi_apb.pstrb := 0xf.U
    }

    def apbAccess(addr: UInt, write: Boolean, data: UInt = 0.U): Unit = {
      spi_apb.psel := true.B
      spi_apb.penable := true.B
      spi_apb.paddr := addr
      spi_apb.pwrite := write.B
      spi_apb.pwdata := data
      spi_apb.pstrb := 0xf.U
    }

    // Default: APB master port idle
    spi_apb.psel := false.B
    spi_apb.penable := false.B
    spi_apb.paddr := 0.U
    spi_apb.pwrite := false.B
    spi_apb.pwdata := 0.U
    spi_apb.pstrb := 0.U
    spi_apb.pprot := 0.U

    // Default: APB slave port not ready
    in.pready := false.B
    in.prdata := 0.U
    in.pslverr := false.B

    // Determine first TX state based on numTxRegs
    val firstTxState = config.numTxRegs match {
      case 1 => State.TX0_Setup
      case 2 => State.TX1_Setup
      case 3 => State.TX2_Setup
      case _ => State.TX3_Setup
    }

    // Determine first RX state based on numRxRegs
    val firstRxState = State.RX0_Setup

    switch(state) {
      is(State.Idle) {
        when(in.psel && !in.penable) {
          flashAddr := in.paddr(config.addrBits - 1, 0)
          isWrite := in.pwrite

          if (config.supportsWrite) {
            // Device supports both read and write
            when(in.pwrite) {
              writeData := in.pwdata
            }
            state := State.SS_Setup
          } else {
            // Read-only device (e.g., Flash)
            when(in.pwrite) {
              state := State.WriteError
              assert(false.B, "XIP write not supported for this device")
            }.otherwise {
              state := State.SS_Setup
            }
          }
        }
      }

      is(State.WriteError) {
        in.pready := true.B
        in.pslverr := true.B
        state := State.Idle
      }

      // SS Setup/Access
      is(State.SS_Setup) {
        apbSetup(ADDR_SS, write = true, ssValue)
        state := State.SS_Access
      }
      is(State.SS_Access) {
        apbAccess(ADDR_SS, write = true, ssValue)
        when(spi_apb.pready) { state := firstTxState }
      }

      // TX3 (only if numTxRegs >= 4)
      is(State.TX3_Setup) {
        if (config.numTxRegs >= 4) {
          apbSetup(ADDR_TX3, write = true, txRegs(0))
          state := State.TX3_Access
        } else {
          state := State.TX2_Setup
        }
      }
      is(State.TX3_Access) {
        if (config.numTxRegs >= 4) {
          apbAccess(ADDR_TX3, write = true, txRegs(0))
          when(spi_apb.pready) { state := State.TX2_Setup }
        }
      }

      // TX2 (only if numTxRegs >= 3)
      is(State.TX2_Setup) {
        if (config.numTxRegs >= 3) {
          val txIdx = if (config.numTxRegs >= 4) 1 else 0
          apbSetup(ADDR_TX2, write = true, txRegs(txIdx))
          state := State.TX2_Access
        } else {
          state := State.TX1_Setup
        }
      }
      is(State.TX2_Access) {
        if (config.numTxRegs >= 3) {
          val txIdx = if (config.numTxRegs >= 4) 1 else 0
          apbAccess(ADDR_TX2, write = true, txRegs(txIdx))
          when(spi_apb.pready) { state := State.TX1_Setup }
        }
      }

      // TX1 (only if numTxRegs >= 2)
      is(State.TX1_Setup) {
        if (config.numTxRegs >= 2) {
          val txIdx = config.numTxRegs match {
            case 4 => 2
            case 3 => 1
            case _ => 0
          }
          apbSetup(ADDR_TX1, write = true, txRegs(txIdx))
          state := State.TX1_Access
        } else {
          state := State.TX0_Setup
        }
      }
      is(State.TX1_Access) {
        if (config.numTxRegs >= 2) {
          val txIdx = config.numTxRegs match {
            case 4 => 2
            case 3 => 1
            case _ => 0
          }
          apbAccess(ADDR_TX1, write = true, txRegs(txIdx))
          when(spi_apb.pready) { state := State.TX0_Setup }
        }
      }

      // TX0 (always needed)
      is(State.TX0_Setup) {
        val txIdx = config.numTxRegs - 1
        apbSetup(ADDR_TX0, write = true, txRegs(txIdx))
        state := State.TX0_Access
      }
      is(State.TX0_Access) {
        val txIdx = config.numTxRegs - 1
        apbAccess(ADDR_TX0, write = true, txRegs(txIdx))
        when(spi_apb.pready) { state := State.Ctrl_Setup }
      }

      // CTRL Setup/Access
      is(State.Ctrl_Setup) {
        apbSetup(ADDR_CTRL, write = true, ctrlValue)
        state := State.Ctrl_Access
      }
      is(State.Ctrl_Access) {
        apbAccess(ADDR_CTRL, write = true, ctrlValue)
        when(spi_apb.pready) { state := State.Poll_Setup }
      }

      // Poll CTRL until GO bit clears
      is(State.Poll_Setup) {
        apbSetup(ADDR_CTRL, write = false)
        state := State.Poll_Access
      }
      is(State.Poll_Access) {
        apbAccess(ADDR_CTRL, write = false)
        when(spi_apb.pready) {
          when(spi_apb.prdata(8)) {
            state := State.Poll_Setup // GO still set
          }.otherwise {
            // Write operation: skip RX read, go directly to deselect
            // Read operation: read RX registers first
            when(isWrite) {
              state := State.DeSSel_Setup
            }.otherwise {
              state := firstRxState
            }
          }
        }
      }

      // RX0 (always read)
      is(State.RX0_Setup) {
        apbSetup(ADDR_RX0, write = false)
        state := State.RX0_Access
      }
      is(State.RX0_Access) {
        apbAccess(ADDR_RX0, write = false)
        when(spi_apb.pready) {
          rxData(0) := spi_apb.prdata
          if (config.numRxRegs >= 2) {
            state := State.RX1_Setup
          } else {
            state := State.DeSSel_Setup
          }
        }
      }

      // RX1 (if numRxRegs >= 2)
      is(State.RX1_Setup) {
        if (config.numRxRegs >= 2) {
          apbSetup(ADDR_RX1, write = false)
          state := State.RX1_Access
        }
      }
      is(State.RX1_Access) {
        if (config.numRxRegs >= 2) {
          apbAccess(ADDR_RX1, write = false)
          when(spi_apb.pready) {
            rxData(1) := spi_apb.prdata
            if (config.numRxRegs >= 3) {
              state := State.RX2_Setup
            } else {
              state := State.DeSSel_Setup
            }
          }
        }
      }

      // RX2 (if numRxRegs >= 3)
      is(State.RX2_Setup) {
        if (config.numRxRegs >= 3) {
          apbSetup(ADDR_RX2, write = false)
          state := State.RX2_Access
        }
      }
      is(State.RX2_Access) {
        if (config.numRxRegs >= 3) {
          apbAccess(ADDR_RX2, write = false)
          when(spi_apb.pready) {
            rxData(2) := spi_apb.prdata
            if (config.numRxRegs >= 4) {
              state := State.RX3_Setup
            } else {
              state := State.DeSSel_Setup
            }
          }
        }
      }

      // RX3 (if numRxRegs >= 4)
      is(State.RX3_Setup) {
        if (config.numRxRegs >= 4) {
          apbSetup(ADDR_RX3, write = false)
          state := State.RX3_Access
        }
      }
      is(State.RX3_Access) {
        if (config.numRxRegs >= 4) {
          apbAccess(ADDR_RX3, write = false)
          when(spi_apb.pready) {
            rxData(3) := spi_apb.prdata
            state := State.DeSSel_Setup
          }
        }
      }

      // DeSSel Setup/Access
      is(State.DeSSel_Setup) {
        apbSetup(ADDR_SS, write = true, 0.U)
        state := State.DeSSel_Access
      }
      is(State.DeSSel_Access) {
        apbAccess(ADDR_SS, write = true, 0.U)
        when(spi_apb.pready) { state := State.Respond }
      }

      // Respond to CPU
      is(State.Respond) {
        in.pready := true.B
        in.pslverr := false.B

        // Combine RX data (RX0 contains lowest bits)
        val rawData = rxData(0) // For single RX reg, just use RX0

        // Apply byte swap if configured
        if (config.swapBytes) {
          in.prdata := Cat(
            rawData(7, 0),
            rawData(15, 8),
            rawData(23, 16),
            rawData(31, 24)
          )
        } else {
          in.prdata := rawData
        }

        state := State.Idle
      }
    }
  }
}
