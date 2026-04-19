package ysyx.sim

import chisel3._
import chisel3.util._

import org.chipsalliance.cde.config.Parameters
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.amba.apb._
import chisel3.util.circt.dpi._
import ysyx.SoCConfig

class APBSyncDisk(address: Seq[AddressSet])(implicit p: Parameters)
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
    val (in, edgeIn) = node.in(0)
    val apbParams = edgeIn.bundle
    val dev = Module(new SyncDiskImpl(apbParams))
    dev.io <> in
  }
}

class SyncDiskImpl(apbParams: APBBundleParameters) extends Module {
  val io = IO(Flipped(new APBBundle(apbParams)))

  object State extends ChiselEnum {
    val idle, access = Value
  }
  val state = RegInit(State.idle)

  io.pready := false.B
  io.pslverr := false.B

  val apbSetup = io.psel && !io.penable
  val offset = (io.paddr - SoCConfig.syncDiskBase.U)(11, 0).pad(64)

  val readEn = apbSetup && !io.pwrite
  val readResult = RawClockedNonVoidFunctionCall(
    "sync_disk_load", SInt(32.W)
  )(clock, readEn, offset)

  val writeEn = WireInit(false.B)
  RawClockedVoidFunctionCall("sync_disk_store")(
    clock, writeEn, offset, io.pwdata.asSInt.pad(32)
  )

  io.prdata := readResult.asUInt

  switch(state) {
    is(State.idle) {
      when(apbSetup) {
        when(io.pwrite) {
          writeEn := true.B
        }
        state := State.access
      }
    }
    is(State.access) {
      io.pready := true.B
      state := State.idle
    }
  }
}
