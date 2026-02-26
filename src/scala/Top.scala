package ysyx

import chisel3._
import chisel3.probe.{Probe, read}
import org.chipsalliance.cde.config.{Parameters, Config}
import freechips.rocketchip.system._
import freechips.rocketchip.diplomacy.LazyModule

import ysyx.core.DebugBundle

object Config {
  def hasChipLink: Boolean = false
  def sdramUseAXI: Boolean = false
}

// module ysyxSoCTop(
//   input clock,
//         reset
// );
//   ysyxSoCFull dut (
//     .clock                (clock),
//     .reset                (reset),
//     .externalPins_uart_rx (1'h0),
//     .externalPins_uart_tx (/* unused */)
//   );
// endmodule
class ysyxSoCTop extends Module {
  implicit val config: Parameters = new Config(new Edge32BitConfig ++ new DefaultRV32Config)

  val io = IO(new Bundle { })
  val dut = LazyModule(new ysyxSoCFull)
  val mdut = Module(dut.module)
  mdut.dontTouchPorts() // mark all ports as don't touch
  mdut.externalPins := DontCare
  mdut.step := true.B  // always step
}

class NPCSoCInterface extends Bundle {
  val clock = Input(Clock())
  val reset = Input(Bool())
  val step  = Input(Bool())
  val debug = Output(new DebugBundle)
}

/** NPCSoC - Top module for Verilator simulation
  *
  * This module wraps ysyxSoCFull and exposes step/debug interfaces
  * for difftest and tracing.
  */
class NPCSoC
    extends FixedIORawModule(new NPCSoCInterface)
    with ImplicitClock
    with ImplicitReset {
  override protected def implicitClock: Clock = io.clock
  override protected def implicitReset: Reset = io.reset

  implicit val config: Parameters = new Config(new Edge32BitConfig ++ new DefaultRV32Config)

  val dut = LazyModule(new ysyxSoCFull)
  val mdut = Module(dut.module)
  mdut.dontTouchPorts()
  mdut.externalPins := DontCare

  mdut.step := io.step
  io.debug := read(mdut.probe)
}

object Elaborate extends App {
  val firtoolOptions = Array("--disable-annotation-unknown")
  circt.stage.ChiselStage.emitSystemVerilogFile(new ysyxSoCTop, args, firtoolOptions)
}

object ElaborateNPCSoC extends App {
  val firtoolOptions = Array("--disable-annotation-unknown")
  circt.stage.ChiselStage.emitSystemVerilogFile(new NPCSoC, args, firtoolOptions)
}
