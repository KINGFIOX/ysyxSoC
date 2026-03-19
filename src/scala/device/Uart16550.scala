package ysyx.device

import chisel3._
import chisel3.util._

import freechips.rocketchip.amba.apb._
import org.chipsalliance.cde.config.Parameters
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.util._

// ============================================================================
// Uart
// ============================================================================

class UARTIO extends Bundle {
  val rx = Input(Bool())
  val tx = Output(Bool())
}

class uart_top_apb extends Module {
  val io = IO(new Bundle {
    val in = Flipped(new APBBundle(APBBundleParameters(addrBits = 32, dataBits = 32)))
    val uart = new UARTIO
    val interrupt = Output(Bool())
  })
  // module
  val core = Module(new uart_regs)
  core.io.clk := clock
  core.io.wb_rst_i := reset

  // modem inputs
  val cts_n = false.B
  val dsr_pad_i = false.B
  val ri_pad_i = false.B
  val dcd_pad_i = false.B
  core.io.modem_inputs := Cat(!cts_n, dsr_pad_i, ri_pad_i, dcd_pad_i)

  // uart tx rx
  core.io.srx_pad_i := io.uart.rx
  io.uart.tx := core.io.stx_pad_o

  // interrupt
  io.interrupt := core.io.int_o

  val apbSetupW = io.in.psel && !io.in.penable

  // pstrb mux
  val wdataW = MuxLookup(io.in.pstrb, 0.U(8.W))(
    Seq(
      "b0001".U(4.W) -> io.in.pwdata(7, 0),
      "b0010".U(4.W) -> io.in.pwdata(15, 8),
      "b0100".U(4.W) -> io.in.pwdata(23, 16),
      "b1000".U(4.W) -> io.in.pwdata(31, 24)
    )
  )
  val addrW = io.in.paddr(2,0) + MuxLookup(io.in.pstrb, 0.U(3.W)) {
    Seq(
      "b0001".U(4.W) -> 0.U,
      "b0010".U(4.W) -> 1.U,
      "b0100".U(4.W) -> 2.U,
      "b1000".U(4.W) -> 3.U
    )
  }

  // latch registers
  val writeDataQ = RegInit(0.U(8.W))
  val addrQ = RegInit(0.U(3.W))

  // core io
  core.io.wb_dat_i := writeDataQ
  core.io.wb_addr_i := addrQ
  io.in.prdata := core.io.wb_dat_o

  // core io defaults
  core.io.wb_we_i := false.B
  core.io.wb_re_i := false.B

  // apb defaults
  io.in.pready := false.B
  io.in.pslverr := false.B

  object State extends ChiselEnum {
    val idle, setup = Value
  }
  val stateQ = RegInit(State.idle)

  switch(stateQ) {
    
    is(State.idle) {
      when(apbSetupW) {
        // latch
        writeDataQ := wdataW
        addrQ := addrW

        // io
        core.io.wb_dat_i := wdataW
        core.io.wb_addr_i := addrW
        when(io.in.pwrite) {
          core.io.wb_we_i := true.B
        } .otherwise {
          assert( io.in.pstrb === 0.U, "read not supported pstrb" )
          core.io.wb_re_i := true.B
        }

        stateQ := State.setup
      }
    }

    is(State.setup) {
      io.in.pready := true.B
      when(io.in.penable) {
        stateQ := State.idle
      }
    }
  }
}

class uart_regs
    extends FixedIOExtModule(new Bundle {
      val clk = Input(Clock())
      val wb_rst_i = Input(Reset())
      val wb_addr_i = Input(UInt(3.W))
      val wb_dat_i = Input(UInt(8.W))
      val wb_dat_o = Output(UInt(8.W))
      val wb_we_i = Input(Bool())
      val wb_re_i = Input(Bool())
      val modem_inputs = Input(UInt(4.W))
      val stx_pad_o = Output(Bool())
      val srx_pad_i = Input(Bool())
      val rts_pad_o = Output(Bool())
      val dtr_pad_o = Output(Bool())
      val int_o = Output(Bool())
    })

class APBUart16550(address: Seq[AddressSet])(implicit p: Parameters)
    extends LazyModule {
  val node = APBSlaveNode(
    Seq(
      APBSlavePortParameters(
        Seq(
          APBSlaveParameters(
            address = address,
            executable = false,
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
    val uart = IO(new UARTIO)
    val interrupt = IO(Output(Bool()))

    val muart = Module(new uart_top_apb)
    muart.io.in <> in
    uart <> muart.io.uart
    interrupt := muart.io.interrupt
  }
}
