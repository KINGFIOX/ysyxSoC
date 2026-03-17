package ysyx

import chisel3._
import chisel3.util._

import org.chipsalliance.cde.config.Parameters
import freechips.rocketchip.subsystem._
import freechips.rocketchip.amba.axi4._
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.util._

import ysyx.core.NPCCore
import ysyx.core.DebugBundle
import ysyx.core.cache.{AXI4DCache, AXI4ICache}
import ysyx.core.common.HasAXIParameter

class CPU(idBits: Int)(implicit p: Parameters)
    extends LazyModule
    with HasAXIParameter {

  // format: off
  private val icacheNode = AXI4MasterNode( Seq( AXI4MasterPortParameters( masters = Seq( AXI4MasterParameters(name = "icache", id = IdRange(0, 1 << idBits))))))
  private val dcacheNode = AXI4MasterNode( Seq( AXI4MasterPortParameters( masters = Seq( AXI4MasterParameters(name = "dcache", id = IdRange(0, 1 << idBits))))))
  private val peripNode = AXI4MasterNode( Seq( AXI4MasterPortParameters( masters = Seq( AXI4MasterParameters(name = "perip", id = IdRange(0, 1 << idBits))))))
  // format: on

  val masterNode = AXI4Xbar()
  // masterNode := icacheNode

  masterNode := icacheNode
  masterNode := dcacheNode
  masterNode := peripNode

  lazy val module = new Impl
  class Impl extends LazyModuleImp(this) {
    // --- io ---
    val (icache, _) = icacheNode.out(0)
    val (dcache, _) = dcacheNode.out(0)
    val (perip, _) = peripNode.out(0)
    val interrupt = IO(Input(Bool()))
    val slave = IO(Flipped(AXI4Bundle(axiParams))) // used for chiplink
    val probe = IO(Output(new DebugBundle))

    // --- modules ---
    val core = Module(new NPCCore)

    // --- connect ---
    probe := core.probe

    icache <> core.icache
    dcache <> core.dcache
    perip <> core.perip

    // Slave interface is not used by NPCCore, tie off
    slave.ar.ready := false.B
    slave.aw.ready := false.B
    slave.w.ready := false.B
    slave.r.valid := false.B
    slave.r.bits := DontCare
    slave.b.valid := false.B
    slave.b.bits := DontCare

    // Interrupt is not used yet
    core.interrupt := interrupt
  }
}
