package ysyx

import chisel3._
import chisel3.util._
import freechips.rocketchip.util.SynchronizerResetType.Async

//               +---+   +---+   +---+   +---+   +---+   +---+   +---+   +---+   +---+   +---+   +---+   +---+   +---+   +---+   +---+   +---+
// SCK            |   |   |   |   |   |   |   |   |   |   |   |   |   |   |   |   |   |   |   |   |   |   |   |   |   |   |   |   |   |   |   |
//        --------+   +---+   +---+   +---+   +---+   +---+   +---+   +---+   +---+   +---+   +---+   +---+   +---+   +---+   +---+   +---+   +------------
//        --------+                                                                                                                               +--------
// SS             |                                                                                                                               |
//                +-------------------------------------------------------------------------------------------------------------------------------+
//        +-------+-------+-------+-------+-------+-------+-------+-------+-------+------------------------------------------------------------------------
//MOSI            |   b7  |   b6  |   b5  |   b4  |   b3  |   b2  |   b1  |   b0  |
//                +-------+-------+-------+-------+-------+-------+-------+-------+
//        +-----------------------------------------------------------------------+-------+-------+-------+-------+-------+-------+-------+-------+--------
//MISO                                                                            |   b0  |   b1  |   b2  |   b3  |   b4  |   b5  |   b6  |   b7  |
//                                                                                +-------+-------+-------+-------+-------+-------+-------+-------+

class bitrev extends RawModule {
  val io = IO(Flipped(new SPIIO(1)))
  val reset = io.ss.asBool.asAsyncReset
  val clock = io.sck.asClock
  val module = withClockAndReset(clock, reset) { Module(new Impl) }
  class Impl extends Module with RequireAsyncReset {
    val io = IO(new Bundle {
      val miso = Output(Bool())
      val mosi = Input(Bool())
    })
    object State extends ChiselEnum {
      val Read, Write = Value
    }
    val state = RegInit(State.Read)
    val counter = Counter(8)
    val data = RegInit(0.U(8.W))
    switch(state) {
      is(State.Read) {
        when(counter.inc()) {
          state := State.Write
        }
      }
      is(State.Write) { }
    }
    when(state === State.Read) {
      data := Cat( data(6, 0), io.mosi )
    } .otherwise {
      data := Cat( 0.U(1.W), data(7, 1) )
    }
    io.miso := data(0)
  }
  io.miso := module.io.miso
  module.io.mosi := io.mosi
}
