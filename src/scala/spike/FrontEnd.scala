package ysyx.spike

import chisel3._
import chisel3.util._
import chisel3.util.circt.dpi.{
  RawClockedNonVoidFunctionCall,
  RawClockedVoidFunctionCall
}

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

  object State extends ChiselEnum {
    val sInit, sFetch, sWait, sValid, sFlush = Value
  }
  val state = RegInit(State.sInit)

  val cur_pc = RegInit(ysyx.SoCConfig.resetVector.U(addrBits.W))
  val saved_dnpc = Reg(UInt(addrBits.W))

  val fetchEn = state === State.sFetch
  val flushEn = state === State.sFlush

  // spike_fe_new() -> handle (u64, WYSIWYG chandle)
  val handle = RawClockedNonVoidFunctionCall("spike_fe_new", UInt(64.W))(
    clock,
    state === State.sInit && !reset.asBool
  )

  // spike_fe_fetch_and_step(handle) -> packed {npc[63:32], inst[31:0]}, all-1 on failure
  val fetchResult =
    RawClockedNonVoidFunctionCall("spike_fe_fetch_and_step", UInt(64.W))(
      clock,
      fetchEn,
      handle
    )
  val fetchOk = fetchResult =/= ~(0.U(64.W))
  val fetchInst = fetchResult(31, 0)
  val fetchNpc = fetchResult(63, 32)

  // spike_fe_set_gpr(handle, idx, val) x 32
  for (i <- 0 until NRReg) {
    RawClockedVoidFunctionCall("spike_fe_set_gpr")(
      clock,
      flushEn,
      handle,
      i.U(32.W),
      flush_gpr(i)
    )
  }

  // spike_fe_set_pc(handle, pc)
  RawClockedVoidFunctionCall("spike_fe_set_pc")(
    clock,
    flushEn,
    handle,
    saved_dnpc
  )

  // Output defaults
  io.out.valid := false.B
  io.out.bits.pc := cur_pc
  io.out.bits.inst := fetchInst
  io.out.bits.predict_npc := fetchNpc
  io.out.bits.ghr := 0.U
  io.out.bits.mcause := 0.U
  io.out.bits.mtval := 0.U
  io.out.bits.has_except := false.B

  switch(state) {
    is(State.sInit) {
      state := State.sFetch
    }

    is(State.sFetch) {
      state := State.sWait
    }

    is(State.sWait) {
      when(fetchOk) {
        state := State.sValid
      }
    }

    is(State.sValid) {
      io.out.valid := true.B
      when(io.out.fire) {
        cur_pc := fetchNpc
        state := State.sFetch
      }
    }

    is(State.sFlush) {
      cur_pc := saved_dnpc
      state := State.sFetch
    }
  }

  when(io.flush && state =/= State.sInit) {
    saved_dnpc := io.redirect.bits.dnpc
    state := State.sFlush
  }
}
