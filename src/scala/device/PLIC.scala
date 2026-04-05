package ysyx.device

import chisel3._
import chisel3.util._

import freechips.rocketchip.amba.apb._
import org.chipsalliance.cde.config.Parameters
import freechips.rocketchip.diplomacy._

// Stub PLIC: reads return 0, writes are ignored.
// Full implementation deferred to xv6 porting phase.
class plic_stub_apb extends Module {
  val io = IO(new Bundle {
    val in = Flipped(
      new APBBundle(APBBundleParameters(addrBits = 32, dataBits = 32))
    )
  })

  object State extends ChiselEnum {
    val idle, access = Value
  }
  private val stateQ = RegInit(State.idle)

  io.in.prdata := 0.U
  io.in.pslverr := false.B
  io.in.pready := stateQ === State.access

  switch(stateQ) {
    is(State.idle) {
      when(io.in.psel) {
        stateQ := State.access
      }
    }
    is(State.access) {
      when(io.in.penable) {
        stateQ := State.idle
      }
    }
  }
}

class APBPLIC(address: Seq[AddressSet])(implicit p: Parameters)
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
    val mplic = Module(new plic_stub_apb)
    mplic.io.in <> in
  }
}
