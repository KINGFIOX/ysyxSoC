package ysyx

import chisel3._
import org.chipsalliance.cde.config.{Parameters, Config}
import freechips.rocketchip.system._
import freechips.rocketchip.diplomacy.LazyModule

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
}

object Elaborate extends App {
  val firtoolOptions = Array("--disable-annotation-unknown")
  circt.stage.ChiselStage.emitSystemVerilogFile(new ysyxSoCTop, args, firtoolOptions)
}
