package ysyx.sim

import chisel3._
import chisel3.util._

import org.chipsalliance.cde.config.Parameters
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.amba.apb._
import chisel3.util.circt.dpi._
import ysyx.SoCConfig

class APBFlash(address: Seq[AddressSet])(implicit p: Parameters)
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
    val (in, edgeIn) = node.in(0)
    val apbParams = edgeIn.bundle
    val mem = Module(new FlashImpl(apbParams))
    mem.io <> in
  }
}

class FlashImpl(apbParams: APBBundleParameters) extends Module {
  val io = IO(Flipped(new APBBundle(apbParams)))

  object State extends ChiselEnum {
    val idle, access = Value
  }
  val state = RegInit(State.idle)

  io.pready := false.B
  io.pslverr := false.B

  val apbSetup = io.psel && !io.penable

  val readEn = apbSetup && ! io.pwrite

  // DPI requires integer args to be 1/8/16/32/64 bits; pad to 64 to match Rust i64
  val paddr64 = (io.paddr - SoCConfig.xipFlashBase.U(32.W)).pad(64)
  val word0_w = RawClockedNonVoidFunctionCall("flash_read", UInt(8.W))(clock, readEn, paddr64)
  val word1_w = RawClockedNonVoidFunctionCall("flash_read", UInt(8.W))(clock, readEn, (paddr64 + 1.U).pad(64))
  val word2_w = RawClockedNonVoidFunctionCall("flash_read", UInt(8.W))(clock, readEn, (paddr64 + 2.U).pad(64))
  val word3_w = RawClockedNonVoidFunctionCall("flash_read", UInt(8.W))(clock, readEn, (paddr64 + 3.U).pad(64))

  // io.prdata := Cat( word0_w, word1_w, word2_w, word3_w )
  io.prdata := Cat( word3_w, word2_w, word1_w, word0_w )

  switch(state) {
    is(State.idle) {
      when(apbSetup) {
        state := State.access
      }
    }
    is(State.access) {
      io.pready := true.B
      state := State.idle
    }
  }
}
