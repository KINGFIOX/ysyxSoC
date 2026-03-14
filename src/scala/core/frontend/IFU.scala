package ysyx.core.frontend

import chisel3._
import chisel3.util._

import freechips.rocketchip.amba.axi4._
import freechips.rocketchip.util._

import ysyx.core.common._
import ysyx.core.lsu._

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

object AXI4Resp {
  val OKAY = 0.U(2.W)
  val EXOKAY = 1.U(2.W)
  val SLVERR = 2.U(2.W)
  val DECERR = 3.U(2.W)
}

class IFU extends NPCModule {

  val io = IO(new Bundle {
    val out = Irrevocable(new IFUOutput)
    val redirect = Input(new RedirectBundle)
  })

  val icache = IO(AXI4Bundle(axiParams))

  private val loadUnit = Module(new LoadUnit(axiParams, 0))

  loadUnit.ar <> icache.ar
  loadUnit.r <> icache.r

  // write disable
  icache.b.ready := false.B
  icache.aw.valid := false.B
  icache.aw.bits := DontCare
  icache.w.valid := false.B
  icache.w.bits := DontCare

  private val pcQ = RegInit(ysyx.SoCConfig.resetVector.U(addrBits.W))
  private val instQ = RegInit(0.U(dataBits.W))
  private val mcauseQ = RegInit(0.U(dataBits.W))
  private val has_except_q = loadUnit.in.resp holdUnless loadUnit.in.data_ok

  object State extends ChiselEnum {
    val idle, addr_req, data_wait, output_wait = Value
  }
  private val stateQ = RegInit(State.idle)

  loadUnit.in.req := (stateQ === State.addr_req)
  loadUnit.in.wr := false.B
  loadUnit.in.size := 2.U
  loadUnit.in.addr := pcQ
  loadUnit.in.wstrb := 0.U
  loadUnit.in.wdata := 0.U

  io.out.valid := (stateQ === State.output_wait)
  io.out.bits.inst := instQ
  io.out.bits.pc := pcQ
  io.out.bits.mcause := mcauseQ
  io.out.bits.mtval := pcQ
  io.out.bits.has_except := has_except_q
  io.out.bits.predict_npc := pcQ + 4.U

  switch(stateQ) {

    is(State.idle) {
      stateQ := State.addr_req
    }

    is(State.addr_req) {
      when(io.redirect.valid) {
        pcQ := io.redirect.correct_npc
      }.elsewhen(loadUnit.in.addr_ok) {
        stateQ := State.data_wait
      }
    }

    is(State.data_wait) {
      when(io.redirect.valid) {
        stateQ := State.addr_req
        pcQ := io.redirect.correct_npc
      }.elsewhen(loadUnit.in.data_ok) {
        stateQ := State.output_wait
        instQ := loadUnit.in.rdata
        when(loadUnit.in.resp =/= AXI4Resp.OKAY) {
          mcauseQ := 1.U // instruction access fault
        }
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
