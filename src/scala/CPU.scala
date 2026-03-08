package ysyx

import chisel3._
import chisel3.util._
import chisel3.probe.{define, Probe}

import org.chipsalliance.cde.config.Parameters
import freechips.rocketchip.subsystem._
import freechips.rocketchip.amba.axi4._
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.util._

import ysyx.core.NPCCore
import ysyx.core.DebugBundle

object CPUAXI4BundleParameters {
  def apply() = AXI4BundleParameters(addrBits = 32, dataBits = 32, idBits = ChipLinkParam.idBits)
}

class CPU(idBits: Int)(implicit p: Parameters) extends LazyModule {

  private val icacheNode = AXI4MasterNode(Seq(AXI4MasterPortParameters(
    masters = Seq(AXI4MasterParameters(
      name = "icache",
      id   = IdRange(0, 1 << idBits))))))

  private val dcacheNode = AXI4MasterNode(Seq(AXI4MasterPortParameters(
    masters = Seq(AXI4MasterParameters(
      name = "dcache",
      id   = IdRange(0, 1 << idBits))))))

  val masterNode = AXI4Xbar()
  masterNode := icacheNode
  // masterNode := AXI4ICache() := icacheNode
  masterNode := AXI4DCache() := dcacheNode

  lazy val module = new Impl
  class Impl extends LazyModuleImp(this) {
    // --- io ---
    val (icache, _) = icacheNode.out(0)
    val (dcache, _) = dcacheNode.out(0)
    val interrupt = IO(Input(Bool()))
    val slave = IO(Flipped(AXI4Bundle(CPUAXI4BundleParameters()))) // used for chiplink
    val probe = IO(Output(Probe(new DebugBundle)))

    // --- modules ---
    val cpu = Module(new NPCCore)

    // --- connect ---
    define(probe, cpu.io.probe)

    icache <> cpu.io.icache
    dcache <> cpu.io.dcache

    // Slave interface is not used by NPCCore, tie off
    slave.ar.ready := false.B
    slave.aw.ready := false.B
    slave.w.ready := false.B
    slave.r.valid := false.B
    slave.r.bits := DontCare
    slave.b.valid := false.B
    slave.b.bits := DontCare

    // Interrupt is not used yet
    cpu.io.interrupt := interrupt
  }
}
