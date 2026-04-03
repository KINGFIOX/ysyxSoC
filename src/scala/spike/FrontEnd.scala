package ysyx.spike

import chisel3._
import chisel3.util._

import ysyx.core.common._
import ysyx.core.frontend.IFUOutput
import ysyx.core.frontend.RedirectBundle

class SpikeFEHelper
    extends FixedIOExtModule(new Bundle {
      val clock         = Input(Clock())
      val init_en       = Input(Bool())
      val fetch_en      = Input(Bool())
      val fetch_inst    = Output(UInt(32.W))
      val fetch_next_pc = Output(UInt(32.W))
      val flush_en      = Input(Bool())
      val flush_gpr     = Input(UInt(1024.W))
      val flush_pc      = Input(UInt(32.W))
    }) {
  setInline(
    "SpikeFEHelper.sv",
    """module SpikeFEHelper(
      |  input        clock,
      |  input        init_en,
      |  input        fetch_en,
      |  output reg [31:0] fetch_inst,
      |  output reg [31:0] fetch_next_pc,
      |  input        flush_en,
      |  input  [1023:0] flush_gpr,
      |  input  [31:0] flush_pc
      |);
      |
      |import "DPI-C" function chandle spike_fe_new();
      |import "DPI-C" function void spike_fe_fetch_and_step(
      |    input chandle h, output int inst, output int next_pc);
      |import "DPI-C" function void spike_fe_set_gpr(
      |    input chandle h, input int idx, input int val);
      |import "DPI-C" function void spike_fe_set_pc(
      |    input chandle h, input int pc);
      |
      |chandle handle;
      |
      |always @(posedge clock) begin
      |  if (init_en) handle = spike_fe_new();
      |  if (fetch_en) spike_fe_fetch_and_step(handle, fetch_inst, fetch_next_pc);
      |  if (flush_en) begin
      |    for (int i = 0; i < 32; i++)
      |      spike_fe_set_gpr(handle, i, flush_gpr[i*32 +: 32]);
      |    spike_fe_set_pc(handle, flush_pc);
      |  end
      |end
      |endmodule
    """.stripMargin
  )
}

class FrontEnd extends NPCModule {
  val io = IO(new Bundle {
    val out      = Decoupled(new IFUOutput)
    val redirect = Input(Valid(new RedirectBundle))
    val flush    = Input(Bool())
  })
  val flush_gpr = IO(Input(Vec(NRReg, UInt(dataBits.W))))

  val helper = Module(new SpikeFEHelper)
  helper.io.clock := clock

  // State machine
  object State extends ChiselEnum {
    val sInit, sFetch, sValid, sFlush = Value
  }
  val state = RegInit(State.sInit)

  val cur_pc     = RegInit(ysyx.SoCConfig.resetVector.U(addrBits.W))
  val saved_dnpc = Reg(UInt(addrBits.W))

  // Helper defaults
  helper.io.init_en   := false.B
  helper.io.fetch_en  := false.B
  helper.io.flush_en  := false.B
  helper.io.flush_gpr := flush_gpr.asUInt
  helper.io.flush_pc  := saved_dnpc

  // Output defaults
  io.out.valid             := false.B
  io.out.bits.pc           := cur_pc
  io.out.bits.inst         := helper.io.fetch_inst
  io.out.bits.predict_npc  := helper.io.fetch_next_pc
  io.out.bits.ghr          := 0.U
  io.out.bits.mcause       := 0.U
  io.out.bits.mtval        := 0.U
  io.out.bits.has_except   := false.B

  switch(state) {
    is(State.sInit) {
      helper.io.init_en := !reset.asBool
      state := State.sFetch
    }

    is(State.sFetch) {
      helper.io.fetch_en := true.B
      state := State.sValid
    }

    is(State.sValid) {
      io.out.valid := true.B
      when(io.out.fire) {
        cur_pc := helper.io.fetch_next_pc
        state := State.sFetch
      }
    }

    is(State.sFlush) {
      helper.io.flush_en := true.B
      cur_pc := saved_dnpc
      state := State.sFetch
    }
  }

  when(io.flush && state =/= State.sInit) {
    saved_dnpc := io.redirect.bits.dnpc
    state := State.sFlush
  }
}
