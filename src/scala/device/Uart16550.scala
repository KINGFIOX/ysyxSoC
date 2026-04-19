package ysyx.device

import chisel3._
import chisel3.util._

import freechips.rocketchip.amba.apb._
import org.chipsalliance.cde.config.Parameters
import freechips.rocketchip.diplomacy._

// ============================================================================
// Fake UART (ns16550 in software via DPI-C)
// ============================================================================
//
// The real uart16550 Verilog IP has been removed.  This module keeps the same
// APB-facing interface but forwards every register access to a C++ software
// model (see npc/src/cpp/dpi/uart_ns16550.cc, a port of spike's ns16550_t).
//
// There is no longer a serial `uart.tx` / `uart.rx` pin pair: characters are
// transported byte-wise across the DPI-C boundary.  NVBoard's UART widget is
// driven directly from the C++ side via `board.uart().Putchar(...)`; likewise
// the keyboard RX queue is polled via `board.uart().Getchar()`.
//
// The interrupt line `interrupt` is sampled once per clock from the C++ model
// via the `uart_tick` DPI call.

// --------------------------- DPI-C shells -----------------------------------

// Synchronous DPI access shell: at each rising clock edge, if `valid` is
// asserted, forward the access to the C++ ns16550 model and latch the read
// data into `rdata`.  Also sample the current interrupt level every cycle.
class UartDpiHelper
    extends FixedIOExtModule(new Bundle {
      val clock = Input(Clock())
      val reset = Input(Reset())
      val valid = Input(Bool())
      val we    = Input(Bool())
      val addr  = Input(UInt(8.W))
      val wdata = Input(UInt(8.W))
      val rdata = Output(UInt(8.W))
      val int_o = Output(Bool())
    }) {
  setInline(
    "UartDpiHelper.sv",
    """module UartDpiHelper(
      |  input         clock,
      |  input         reset,
      |  input         valid,
      |  input         we,
      |  input  [7:0]  addr,
      |  input  [7:0]  wdata,
      |  output reg [7:0] rdata,
      |  output reg    int_o
      |);
      |import "DPI-C" function void uart_req(
      |    input  bit   we,
      |    input  byte  addr,
      |    input  byte  wdata,
      |    output byte  rdata);
      |import "DPI-C" function void uart_tick(output bit int_o);
      |byte dpi_rdata;
      |bit  dpi_int;
      |always @(posedge clock) begin
      |  if (reset) begin
      |    rdata <= 8'h00;
      |    int_o <= 1'b0;
      |  end else begin
      |    if (valid) begin
      |      uart_req(we, addr, wdata, dpi_rdata);
      |      rdata <= dpi_rdata;
      |    end
      |    uart_tick(dpi_int);
      |    int_o <= dpi_int;
      |  end
      |end
      |endmodule
    """.stripMargin
  )
}

// --------------------------- APB wrapper ------------------------------------

class uart_top_apb extends Module {
  val io = IO(new Bundle {
    val in        = Flipped(new APBBundle(APBBundleParameters(addrBits = 32, dataBits = 32)))
    val interrupt = Output(Bool())
  })

  val dpi = Module(new UartDpiHelper)
  dpi.io.clock := clock
  dpi.io.reset := reset
  io.interrupt := dpi.io.int_o

  val apbSetupW = io.in.psel && !io.in.penable

  // Byte-accurate APB address: the master (LSU) drives `paddr` to the exact
  // byte offset being accessed and sets `pstrb` to the corresponding lane.
  // Select wdata lane from `paddr(1,0)`.
  val addrW = io.in.paddr(2, 0)
  val wdataW = MuxLookup(io.in.paddr(1, 0), 0.U(8.W))(
    Seq(
      "b00".U(2.W) -> io.in.pwdata(7, 0),
      "b01".U(2.W) -> io.in.pwdata(15, 8),
      "b10".U(2.W) -> io.in.pwdata(23, 16),
      "b11".U(2.W) -> io.in.pwdata(31, 24)
    )
  )

  // DPI request strobe and operands.  Fire exactly one DPI `uart_req` per APB
  // transfer, on the transition idle -> setup.
  val reqValid = WireInit(false.B)
  dpi.io.valid := reqValid
  dpi.io.we    := io.in.pwrite
  dpi.io.addr  := Cat(0.U(5.W), addrW)
  dpi.io.wdata := wdataW

  // For reads, replicate the decoded byte across all four APB lanes.  The
  // master is expected to honour `pstrb` / `paddr(1,0)` and extract the right
  // lane itself.
  io.in.prdata  := Cat(dpi.io.rdata, dpi.io.rdata, dpi.io.rdata, dpi.io.rdata)
  io.in.pslverr := false.B
  io.in.pready  := false.B

  object State extends ChiselEnum {
    val idle, setup = Value
  }
  val stateQ = RegInit(State.idle)

  switch(stateQ) {
    is(State.idle) {
      when(apbSetupW) {
        reqValid := true.B
        stateQ   := State.setup
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

// --------------------------- Diplomatic node --------------------------------

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
    val (in, _)   = node.in(0)
    val interrupt = IO(Output(Bool()))

    val muart = Module(new uart_top_apb)
    muart.io.in <> in
    interrupt := muart.io.interrupt
  }
}
