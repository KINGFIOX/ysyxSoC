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
    val flush = Input(Bool())
  })

  val ifu_ = Module(new IFU)
  ifu_.icache <> icache

  val pdu_ = Module(new Predict)
  pdu_.io.predict <> ifu_.io.predict

  pdu_.io.redirect := io.redirect

  io.out <> ifu_.io.out

  ifu_.io.flush := io.flush
  ifu_.io.snpc := io.redirect.bits.snpc
}
