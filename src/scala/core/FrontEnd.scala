package ysyx.core.frontend

import chisel3._
import chisel3.util._

import freechips.rocketchip.amba.axi4._

import ysyx.core.common._
import ysyx.core.sram._

class FrontEnd extends NPCModule {

  val icache = IO(SRAMBundle(sramParams))

  val io = IO(new Bundle {
    val out = Decoupled(new IFUOutput)
    val redirect = Input(Valid(new RedirectBundle))
  })

  val ifu_ = Module(new IFU)

  ifu_.io.predict.bits.dnpc := ifu_.io.predict.bits.pc + 4.U

  ifu_.icache <> icache
  io.out <> ifu_.io.out
  // Only redirect on commit; bits are combinational from ROB head when valid is false.
  ifu_.io.redirect.mispredict := io.redirect.valid && io.redirect.bits.mispredict
  ifu_.io.redirect.dnpc := io.redirect.bits.dnpc
}
