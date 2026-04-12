package ysyx.core.frontend

import chisel3._
import chisel3.util._

import freechips.rocketchip.amba.axi4._

import ysyx.core.common._
import ysyx.core.sram._

class FrontEnd extends NPCModule {

  val icache = IO(SRAMBundle(sramParams))
  val ptw_port = IO(SRAMBundle(sramParams))

  val io = IO(new Bundle {
    val out = Decoupled(new IFUOutput)
    val redirect = Input(Valid(new RedirectBundle))
    val flush = Input(Bool())
    val satp = Input(UInt(dataBits.W))
    val priv = Input(UInt(2.W))
    val sfence_vma = Input(Bool())
  })

  val ifu_ = Module(new IFU)
  ifu_.icache <> icache
  ifu_.ptw_port <> ptw_port

  val pdu_ = Module(new Predict)
  pdu_.io.predict <> ifu_.io.predict

  pdu_.io.redirect := io.redirect

  io.out <> ifu_.io.out

  ifu_.io.flush := io.flush
  ifu_.io.dnpc := io.redirect.bits.dnpc
  ifu_.io.satp := io.satp
  ifu_.io.priv := io.priv
  ifu_.io.sfence_vma := io.sfence_vma
}
