package ysyx.core

import chisel3._
import chisel3.util._

import ysyx.core.common.NPCModule
import ysyx.core.component._
import freechips.rocketchip.amba.axi4._

class FrontEnd extends NPCModule {

  val icache = IO(AXI4Bundle(axiParams))

  val io = IO(new Bundle {
    val out      = Irrevocable(new IFUOutput)
    val redirect = Input(new RedirectBundle)
  })

  val ifu_ = Module(new IFU)

  ifu_.icache <> icache
  io.out <> ifu_.io.out
  ifu_.io.redirect := io.redirect
}
