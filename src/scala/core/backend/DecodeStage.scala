package ysyx.core.backend

import chisel3._
import chisel3.util._
import chisel3.probe.{define, Probe}

import ysyx.core.common._
import ysyx.core.frontend._
import chisel3.probe.Probe

class DecodeStageOutput extends NPCBundle {
  val rs1_idx = UInt(NRRegbits.W)
  val rs2_idx = UInt(NRRegbits.W)
  val rd_idx = UInt(NRRegbits.W)
  val imm = UInt(dataBits.W)
  val ctrl = new CUOutputBase
  val pc = UInt(addrBits.W)
  val inst = Probe(UInt(instBits.W))
  val inst_bits = UInt(instBits.W)
  val predict_npc = UInt(addrBits.W)
  val mcause = UInt(dataBits.W)
  val mtval = UInt(dataBits.W)
  val has_except = Bool()
  val is_call = Bool()
  val is_ret = Bool()
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

  val rd_idx = ifu_out.inst(11, 7)
  val rs1_idx = ifu_out.inst(19, 15)

  val out = io.out.bits
  out.rs1_idx := rs1_idx
  out.rs2_idx := ifu_out.inst(24, 20)
  out.rd_idx := rd_idx
  out.imm := imm
  out.ctrl := cu_out
  out.mcause := Mux(ifu_out.has_except, ifu_out.mcause, cu_out.mcause)
  out.mtval := Mux(ifu_out.has_except, ifu_out.mtval, cu_out.mtval)
  out.has_except := ifu_out.has_except || cu_out.has_except
  out.is_call := (cu_out.inst_type === InstType.JAL) && (rd_idx === 1.U)
  out.is_ret := (cu_out.inst_type === InstType.JALR) && (imm === 0.U) && (rs1_idx === 1.U) && (rd_idx === 0.U)

  out.pc := ifu_out.pc
  out.inst_bits := ifu_out.inst
  out.predict_npc := ifu_out.predict_npc
  define(out.inst, ifu_out.inst)
}
