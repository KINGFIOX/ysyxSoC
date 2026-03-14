package ysyx.core.frontend

import chisel3._
import chisel3.util._

import freechips.rocketchip.amba.axi4._

import ysyx.core.common._

class FrontEnd extends NPCModule {

  val icache = IO(AXI4Bundle(axiParams))

  val io = IO(new Bundle {
    val out = Decoupled(new IFUOutput)
    val redirect = Input(new RedirectBundle)
  })

  val ifu_ = Module(new IFU)

  ifu_.icache <> icache
  io.out <> ifu_.io.out
  ifu_.io.redirect := io.redirect
}
