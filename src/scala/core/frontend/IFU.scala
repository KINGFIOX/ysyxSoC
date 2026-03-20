package ysyx.core.frontend

import chisel3._
import chisel3.util._

import freechips.rocketchip.amba.axi4._
import freechips.rocketchip.util._

import ysyx.core.common._
import ysyx.core.lsu._
import ysyx.core.sram._

class IFUOutput extends NPCBundle {
  val inst = UInt(instBits.W)
  val pc = UInt(addrBits.W)
  val predict_npc = UInt(addrBits.W)
  val mcause = UInt(dataBits.W)
  val mtval = UInt(dataBits.W) // access fault address
  val has_except = Bool()
}

class RedirectBundle extends NPCBundle {
  val valid = Bool()
  val correct_npc = UInt(addrBits.W)
  val wrong_pc = UInt(addrBits.W) // the pc of mispredicted instruction
}

class IFU extends NPCModule {

  val io = IO(new Bundle {
    val out = Irrevocable(new IFUOutput)
    val redirect = Input(new RedirectBundle)
  })

  val icache = IO(SRAMBundle(sramParams))

  private val pcQ = RegInit(ysyx.SoCConfig.resetVector.U(addrBits.W))
  private val instQ = RegInit(0.U(dataBits.W))

  object State extends ChiselEnum {
    val idle, addr_req, data_wait, output_wait = Value
  }
  private val stateQ = RegInit(State.idle)

  // icache defaults (read-only, word-sized fetch)
  icache.req := (stateQ === State.addr_req)
  icache.wen := false.B
  icache.size := 2.U
  icache.addr := pcQ
  icache.wstrb := 0.U
  icache.wdata := 0.U

  io.out.valid := (stateQ === State.output_wait)
  io.out.bits.inst := instQ
  io.out.bits.pc := pcQ
  io.out.bits.mcause := 0.U
  io.out.bits.mtval := 0.U
  io.out.bits.has_except := false.B
  io.out.bits.predict_npc := pcQ + 4.U

  switch(stateQ) {

    is(State.idle) {
      stateQ := State.addr_req
    }

    is(State.addr_req) {
      when(io.redirect.valid) {
        pcQ := io.redirect.correct_npc
      }.elsewhen(icache.ack) {
        stateQ := State.data_wait
      }
    }

    is(State.data_wait) {
      when(io.redirect.valid) {
        stateQ := State.addr_req
        pcQ := io.redirect.correct_npc
      }.elsewhen(icache.done) {
        stateQ := State.output_wait
        instQ := icache.rdata
      }
    }

    is(State.output_wait) {
      when(io.redirect.valid) {
        stateQ := State.addr_req
        pcQ := io.redirect.correct_npc
      }.elsewhen(io.out.fire) {
        stateQ := State.addr_req
        pcQ := pcQ + 4.U
      }
    }

  }
}
