package ysyx.core.frontend

import chisel3._
import chisel3.util._

import freechips.rocketchip.amba.axi4._
import freechips.rocketchip.util._

import ysyx.core.common._
import ysyx.core.lsu._
import ysyx.core.sram._
import ysyx.core.backend.InstType

class IFUOutput extends NPCBundle {
  val inst = UInt(instBits.W)
  val pc = UInt(addrBits.W)
  val predict_npc = UInt(addrBits.W)
  val mcause = UInt(dataBits.W)
  val mtval = UInt(dataBits.W) // access fault address
  val has_except = Bool()
}

class RedirectBundle extends RedirectBase {
  val wrong_pc = UInt(addrBits.W) // the pc of mispredicted instruction
  val inst_type = InstType()
  val is_call = Bool()
  val is_ret = Bool()
}

class RedirectBase extends NPCBundle {
  val snpc = UInt(addrBits.W)
  val mispredict = Bool()
}

class PredictBundle extends NPCBundle {
  val dnpc = UInt(addrBits.W)
  val pc = Input(UInt(addrBits.W))
  val inst = Input(UInt(instBits.W))
}

class IFU extends NPCModule {

  val io = IO(new Bundle {
    val out = Irrevocable(new IFUOutput)
    // correct when mispredict
    val predict = Flipped(new PredictBundle)
    val flush = Input(Bool())
    val snpc = Input(UInt(dataBits.W)) // correct pc
  })

  val icache = IO(SRAMBundle(sramParams))

  val pc_q = RegInit(ysyx.SoCConfig.resetVector.U(addrBits.W))
  val inst_q = icache.rdata holdUnless icache.done

  object State extends ChiselEnum {
    val idle, addr_req, data_wait, output_wait = Value
  }
  val state_q = RegInit(State.idle)

  // icache defaults (read-only, word-sized fetch)
  icache.req := (state_q === State.addr_req)
  icache.wen := false.B
  icache.size := 2.U
  icache.addr := pc_q
  icache.wstrb := 0.U
  icache.wdata := 0.U

  io.out.valid := (state_q === State.output_wait)
  io.out.bits.inst := inst_q
  io.out.bits.pc := pc_q
  io.out.bits.mcause := 0.U
  io.out.bits.mtval := 0.U
  io.out.bits.has_except := false.B
  io.out.bits.predict_npc := io.predict.dnpc
  io.predict.pc := pc_q
  io.predict.inst := inst_q

  switch(state_q) {

    is(State.idle) {
      state_q := State.addr_req
    }

    is(State.addr_req) {
      when(icache.ack) {
        state_q := State.data_wait
      }
    }

    is(State.data_wait) {
      when(icache.done) {
        state_q := State.output_wait
      }
    }

    is(State.output_wait) {
      when(io.out.fire) {
        state_q := State.addr_req
        pc_q := io.predict.dnpc
      }
    }

  }

  when(io.flush) {
    state_q := State.addr_req
    pc_q := io.snpc
  }

}
