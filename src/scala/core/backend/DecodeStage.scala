package ysyx.core.backend

import chisel3._
import chisel3.util._

import ysyx.core.common._
import ysyx.core.frontend._
import chisel3.probe.Probe

class DecodeStageOutput extends NPCBundle {
  val rs1_idx = UInt(NRRegbits.W)
  val rs2_idx = UInt(NRRegbits.W)
  val rd_idx = UInt(NRRegbits.W)
  val imm = UInt(dataBits.W)
  val ifu = new IFUOutput
  val ctrl = new CUOutput
}

class DecodeStage extends NPCModule {
  val io = IO(new Bundle {
    val in = Flipped(Decoupled(new IFUOutput))
    val out = Decoupled(new DecodeStageOutput)
  })

  // combinational backpress
  io.in.ready := io.out.ready
  io.out.valid := io.in.valid

  val cu_ = Module(new CU)
  val igu_ = Module(new IGU)

  val ifu_out = io.in.bits
  cu_.io.in.inst := ifu_out.inst
  cu_.io.in.pc := ifu_out.pc
  igu_.io.in.inst_31_7 := ifu_out.inst(31, 7)
  igu_.io.in.imm_type := cu_.io.out.imm_type
  val cu_out = cu_.io.out
  val imm = igu_.io.out.imm

  val o = io.out.bits
  o.rs1_idx := ifu_out.inst(19, 15)
  o.rs2_idx := ifu_out.inst(24, 20)
  o.rd_idx := ifu_out.inst(11, 7)
  o.imm := imm
  o.ifu := ifu_out
  o.ctrl := cu_out

}
