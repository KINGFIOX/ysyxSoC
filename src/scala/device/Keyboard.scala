package ysyx

import chisel3._
import chisel3.util._

import freechips.rocketchip.amba.apb._
import org.chipsalliance.cde.config.Parameters
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.util._

class PS2IO extends Bundle {
  val clk = Input(Bool())
  val data = Input(Bool())
}

case class PS2KeybrdParams(entries: Int = 8) { }

class PS2KeybrdCore(val p: PS2KeybrdParams = PS2KeybrdParams()) extends Module {
  val io = IO(new Bundle {
    val ps2 = new PS2IO
    val nextdata = Input(Bool())
    val data = Output(UInt(8.W))
    val valid = Output(Bool())
    val overflow = Output(Bool())
  })

  // buffer, hold one byte of data
  // one byte consist of 11bit
  // [start, b0, b1, b2, b3, b4, b5, b6, b7, parity, stop]
  val buffer = Reg(Vec(10, Bool()))
  val count = RegInit(0.U(4.W))

  // data queue
  val queue = Module(new Queue(UInt(8.W), entries = p.entries))
  queue.io.enq.valid := false.B
  queue.io.enq.bits := Cat( buffer(8), buffer(7), buffer(6), buffer(5), buffer(4), buffer(3), buffer(2), buffer(1))
  queue.io.deq.ready := false.B

  // clock sync
  val ps2clk0Q = RegNext(io.ps2.clk)
  val ps2clk1Q = RegNext(ps2clk0Q)
  val ps2clk2Q = RegNext(ps2clk1Q)
  val samplingW = ps2clk2Q && !ps2clk1Q

  // io
  io.data := queue.io.deq.bits
  io.valid := queue.io.deq.valid
  // overflow
  // enq.valid -> !enq.ready
  io.overflow := false.B
  when(queue.io.enq.valid) {
    when(!queue.io.enq.ready) { // missing handshake
      io.overflow := true.B
    }
  }

  when(queue.io.deq.valid) {
    when(io.nextdata) {
      queue.io.deq.ready := true.B
    }
  }

  when(samplingW) {
    when(count === 10.U) {
      val startOk = !buffer(0)
      val stopOk = io.ps2.data
      // buffer[1:10) <=> buffer[1:9]
      val parityOk = buffer.slice(1, 10).reduce(_ ^ _)
      when(startOk && stopOk && parityOk) {
        queue.io.enq.valid := true.B
      }
      count := 0.U
    }.otherwise {
      buffer(count) := io.ps2.data
      count := count + 1.U
    }
  }
}

class PS2CtrlIO extends Bundle {
  val in = Flipped(
    new APBBundle(APBBundleParameters(addrBits = 32, dataBits = 32))
  )
  val ps2 = new PS2IO
}

class ps2_top_apb extends Module {
  val io = IO(new PS2CtrlIO)
  io.in.prdata := 0.U
  io.in.pready := true.B
  io.in.pslverr := false.B

  // --- states ---
  object State extends ChiselEnum {
    val idle, access, ready = Value
  }
  val state = RegInit(State.idle)

  // --- registers ---
  val rdataQ = RegInit(0.U(8.W))

  // --- modules ---
  val core = Module(new PS2KeybrdCore)
  core.io.ps2 <> io.ps2
  core.io.nextdata := false.B

  // --- outputs ---
  io.in.prdata := rdataQ
  io.in.pslverr := false.B
  io.in.pready := (state === State.ready)

  // --- state machine ---
  switch(state) {

    is(State.idle) {
      when(io.in.psel) {
        state := State.access
      }
    }

    is(State.access) {
      // read only
      rdataQ := core.io.data
      core.io.nextdata := true.B

      state := State.ready
    }

    is(State.ready) {
      when(io.in.penable) {
        state := State.idle
      }
    }

  }

}

class APBKeyboard(address: Seq[AddressSet])(implicit p: Parameters)
    extends LazyModule {
  val node = APBSlaveNode(
    Seq(
      APBSlavePortParameters(
        Seq(
          APBSlaveParameters(
            address = address,
            executable = true,
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
    val ps2_bundle = IO(new PS2IO)

    val mps2 = Module(new ps2_top_apb)
    mps2.io.in <> in
    ps2_bundle <> mps2.io.ps2
  }
}
