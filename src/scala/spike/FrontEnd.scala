package ysyx.spike

import chisel3._
import chisel3.util._

import ysyx.core.common._
import ysyx.core.frontend.IFUOutput
import ysyx.core.frontend.RedirectBundle

class FrontEnd extends NPCModule {
  val io = IO(new Bundle {
    val out = Decoupled(new IFUOutput)
    val redirect = Input(Valid(new RedirectBundle))
    val flush = Input(Bool())
  })
  val flush_gpr = IO(Input(Vec(NRReg, UInt(dataBits.W))))

}