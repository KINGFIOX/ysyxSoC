package ysyx.device

import chisel3._
import chisel3.util._

import freechips.rocketchip.amba.apb._
import org.chipsalliance.cde.config.Parameters
import freechips.rocketchip.diplomacy._

// RISC-V standard CLINT register offsets (single-hart)
//   msip[0]      : 0x0000  (32-bit R/W)
//   mtimecmp[0]  : 0x4000  (64-bit R/W, two 32-bit APB accesses)
//   mtime        : 0xBFF8  (64-bit RO counter, two 32-bit APB accesses)
class clint_top_apb extends Module {
  val io = IO(new Bundle {
    val in = Flipped(
      new APBBundle(APBBundleParameters(addrBits = 32, dataBits = 32))
    )
    val timer_irq = Output(Bool())
    val mtime_out = Output(UInt(64.W))
  })

  private val mtime = RegInit(0.U(64.W))
  mtime := mtime + 1.U

  private val mtimecmp = RegInit(~0.U(64.W))
  private val msip = RegInit(0.U(32.W))

  io.timer_irq := mtime >= mtimecmp
  io.mtime_out := mtime

  object State extends ChiselEnum {
    val idle, access = Value
  }
  private val stateQ = RegInit(State.idle)
  private val rdataQ = RegInit(0.U(32.W))

  io.in.prdata := rdataQ
  io.in.pslverr := false.B
  io.in.pready := stateQ === State.access

  private val paddr = io.in.paddr(15, 0)

  switch(stateQ) {
    is(State.idle) {
      when(io.in.psel) {
        stateQ := State.access

        when(io.in.pwrite) {
          switch(paddr) {
            is(0x0000.U) { msip := io.in.pwdata }
            is(0x4000.U) {
              mtimecmp := Cat(mtimecmp(63, 32), io.in.pwdata)
            }
            is(0x4004.U) {
              mtimecmp := Cat(io.in.pwdata, mtimecmp(31, 0))
            }
          }
        }.otherwise {
          switch(paddr) {
            is(0x0000.U) { rdataQ := msip }
            is(0x4000.U) { rdataQ := mtimecmp(31, 0) }
            is(0x4004.U) { rdataQ := mtimecmp(63, 32) }
            is(0xBFF8.U) { rdataQ := mtime(31, 0) }
            is(0xBFFC.U) { rdataQ := mtime(63, 32) }
          }
        }
      }
    }

    is(State.access) {
      when(io.in.penable) {
        stateQ := State.idle
      }
    }
  }
}

class APBCLINT(address: Seq[AddressSet])(implicit p: Parameters)
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
    val timer_irq = IO(Output(Bool()))
    val mtime_out = IO(Output(UInt(64.W)))

    val mclint = Module(new clint_top_apb)
    mclint.io.in <> in
    timer_irq := mclint.io.timer_irq
    mtime_out := mclint.io.mtime_out
  }
}
