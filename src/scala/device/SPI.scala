package ysyx

import chisel3._
import chisel3.util._

import freechips.rocketchip.amba.apb._
import org.chipsalliance.cde.config.Parameters
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.util._

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
    val in =
      Flipped(new APBBundle(APBBundleParameters(addrBits = 32, dataBits = 32)))
    val spi = new SPIIO
    val spi_irq_out = Output(Bool())
  })
}

class flash extends BlackBox {
  val io = IO(Flipped(new SPIIO(1)))
}

class APBSPI(address: Seq[AddressSet])(implicit p: Parameters) extends LazyModule {
  val spiNode = APBSlaveNode( Seq(
      APBSlavePortParameters( Seq(
          APBSlaveParameters(
            address = address,
            executable = true,
            supportsRead = true,
            supportsWrite = true
          ) ),
        beatBytes = 4
      ) ) )

  val xipBridge = LazyModule(new APBXIPBridge)
  spiNode := xipBridge.node
  val node = xipBridge.node

  lazy val module = new Impl
  class Impl extends LazyModuleImp(this) {
    val (in, _) = spiNode.in(0)
    val spi_bundle = IO(new SPIIO)

    val mspi = Module(new spi_top_apb)
    mspi.io.clock := clock
    mspi.io.reset := reset
    mspi.io.in <> in
    spi_bundle <> mspi.io.spi
  }
}

class APBXIPBridge(implicit p: Parameters) extends LazyModule {
  val node = APBIdentityNode()

  lazy val module = new Impl
  class Impl extends LazyModuleImp(this) {
    (node.in zip node.out) foreach { case ((in, _), (out, _)) =>
      // XIP address detection
      val isXIP = SoCConfig.xipFlashBase.U(32.W) <= in.paddr && in.paddr < (SoCConfig.xipFlashBase + SoCConfig.xipFlashSize).U

      // State machine with all steps expanded
      // 7-step SPI flash read sequence:
      //   SS:     Write SS=1        (flash select)
      //   TX1:    Write TX1=cmd|addr (0x03 + 24-bit addr)
      //   TX0:    Write TX0=0       (data placeholder)
      //   Ctrl:   Write CTRL=0x2140 (ASS | GO | CHAR_LEN(64))
      //   Poll:   Read  CTRL        (poll until GO bit clears)
      //   RX:     Read  RX0         (capture received data)
      //   DeSSel: Write SS=0        (flash deselect)
      object State extends ChiselEnum {
        val Passthrough,
        XIPWriteError,  // XIP write not supported, return error
        SS_Setup, SS_Access,
        TX1_Setup, TX1_Access,
        TX0_Setup, TX0_Access,
        Ctrl_Setup, Ctrl_Access,
        Poll_Setup, Poll_Access,
        RX_Setup, RX_Access,
        DeSSel_Setup, DeSSel_Access,
        Respond = Value
      }
      val state = RegInit(State.Passthrough)

      // Captured flash address and received data
      val flashAddr = Reg(UInt(24.W))
      val rxData = Reg(UInt(32.W))

      // SPI register addresses
      val ADDR_SS = "h18".U(32.W)
      val ADDR_TX1 = "h04".U(32.W)
      val ADDR_TX0 = "h00".U(32.W)
      val ADDR_CTRL = "h10".U(32.W)
      val ADDR_RX0 = "h00".U(32.W)

      // TX1 value: 0x03 (read command) + 24-bit address
      val tx1Val = Cat("h03".U(8.W), flashAddr)

      // Default output values
      out.psel := false.B
      out.penable := false.B
      out.paddr := 0.U
      out.pwrite := false.B
      out.pwdata := 0.U
      out.pstrb := 0.U
      out.pprot := 0.U
      in.pready := false.B
      in.prdata := 0.U
      in.pslverr := false.B

      switch(state) {
        is(State.Passthrough) {
          // Default: passthrough all APB signals
          out.psel := in.psel
          out.penable := in.penable
          out.paddr := in.paddr
          out.pwrite := in.pwrite
          out.pwdata := in.pwdata
          out.pstrb := in.pstrb
          out.pprot := in.pprot
          in.pready := out.pready
          in.prdata := out.prdata
          in.pslverr := out.pslverr

          // Detect XIP access in SETUP phase (psel=1, penable=0)
          when(in.psel && !in.penable && isXIP) {
            out.psel := false.B
            when(in.pwrite) {
              // XIP write not supported, return error
              state := State.XIPWriteError
              assert(false.B, "XIP write not supported")
            }.otherwise {
              // XIP read
              flashAddr := in.paddr(23, 0)
              state := State.SS_Setup
              // printf("[XIP] Capture: in.paddr=0x%x, flashAddr=0x%x\n", in.paddr, in.paddr(23, 0))
            }
          }
        }

        // XIP write error: flash does not support write
        is(State.XIPWriteError) {
          in.pready := true.B
          in.pslverr := true.B
          state := State.Passthrough
        }

        // Step 0: Write SS=1 (select flash)
        is(State.SS_Setup) {
          out.psel := true.B
          out.penable := false.B
          out.paddr := ADDR_SS
          out.pwrite := true.B
          out.pwdata := 1.U
          out.pstrb := "hf".U
          state := State.SS_Access
        }
        is(State.SS_Access) {
          out.psel := true.B
          out.penable := true.B
          out.paddr := ADDR_SS
          out.pwrite := true.B
          out.pwdata := 1.U
          out.pstrb := "hf".U
          when(out.pready) { state := State.TX1_Setup }
        }

        // Step 1: Write TX1 = cmd | addr
        is(State.TX1_Setup) {
          out.psel := true.B
          out.penable := false.B
          out.paddr := ADDR_TX1
          out.pwrite := true.B
          out.pwdata := tx1Val
          out.pstrb := "hf".U
          state := State.TX1_Access
          // printf("[XIP] TX1_Setup: flashAddr=0x%x, tx1Val=0x%x\n", flashAddr, tx1Val)
        }
        is(State.TX1_Access) {
          out.psel := true.B
          out.penable := true.B
          out.paddr := ADDR_TX1
          out.pwrite := true.B
          out.pwdata := tx1Val
          out.pstrb := "hf".U
          when(out.pready) { state := State.TX0_Setup }
        }

        // Step 2: Write TX0 = 0 (placeholder for received data)
        is(State.TX0_Setup) {
          out.psel := true.B
          out.penable := false.B
          out.paddr := ADDR_TX0
          out.pwrite := true.B
          out.pwdata := 0.U
          out.pstrb := "hf".U
          state := State.TX0_Access
        }
        is(State.TX0_Access) {
          out.psel := true.B
          out.penable := true.B
          out.paddr := ADDR_TX0
          out.pwrite := true.B
          out.pwdata := 0.U
          out.pstrb := "hf".U
          when(out.pready) { state := State.Ctrl_Setup }
        }

        // Step 3: Write CTRL = 0x2140 (ASS | GO | CHAR_LEN(64))
        is(State.Ctrl_Setup) {
          out.psel := true.B
          out.penable := false.B
          out.paddr := ADDR_CTRL
          out.pwrite := true.B
          out.pwdata := "h2140".U
          out.pstrb := "hf".U
          state := State.Ctrl_Access
        }
        is(State.Ctrl_Access) {
          out.psel := true.B
          out.penable := true.B
          out.paddr := ADDR_CTRL
          out.pwrite := true.B
          out.pwdata := "h2140".U
          out.pstrb := "hf".U
          when(out.pready) { state := State.Poll_Setup }
        }

        // Step 4: Read CTRL, poll until GO bit clears
        is(State.Poll_Setup) {
          out.psel := true.B
          out.penable := false.B
          out.paddr := ADDR_CTRL
          out.pwrite := false.B
          state := State.Poll_Access
        }
        is(State.Poll_Access) {
          out.psel := true.B
          out.penable := true.B
          out.paddr := ADDR_CTRL
          out.pwrite := false.B
          when(out.pready) {
            when(out.prdata(8)) {
              // GO bit still set, keep polling
              state := State.Poll_Setup
            }.otherwise {
              // GO bit cleared, proceed to read RX0
              state := State.RX_Setup
            }
          }
        }

        // Step 5: Read RX0 (capture received data)
        is(State.RX_Setup) {
          out.psel := true.B
          out.penable := false.B
          out.paddr := ADDR_RX0
          out.pwrite := false.B
          state := State.RX_Access
        }
        is(State.RX_Access) {
          out.psel := true.B
          out.penable := true.B
          out.paddr := ADDR_RX0
          out.pwrite := false.B
          when(out.pready) {
            rxData := out.prdata
            state := State.DeSSel_Setup
          }
        }

        // Step 6: Write SS=0 (deselect flash)
        is(State.DeSSel_Setup) {
          out.psel := true.B
          out.penable := false.B
          out.paddr := ADDR_SS
          out.pwrite := true.B
          out.pwdata := 0.U
          out.pstrb := "hf".U
          state := State.DeSSel_Access
        }
        is(State.DeSSel_Access) {
          out.psel := true.B
          out.penable := true.B
          out.paddr := ADDR_SS
          out.pwrite := true.B
          out.pwdata := 0.U
          out.pstrb := "hf".U
          when(out.pready) { state := State.Respond }
        }

        // Final: Return data to upstream
        is(State.Respond) {
          in.pready := true.B
          // Byte-swap: SPI receives MSB-first but RISC-V is little-endian
          in.prdata := Cat(
            rxData(7, 0),
            rxData(15, 8),
            rxData(23, 16),
            rxData(31, 24)
          )
          in.pslverr := false.B
          state := State.Passthrough
        }
      }
    }
  }
}
