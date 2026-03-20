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

  ifu_.icache <> icache
  io.out <> ifu_.io.out
  ifu_.io.redirect.valid := io.redirect.valid
  ifu_.io.redirect.bits.correct_npc := io.redirect.bits.correct_npc
}
