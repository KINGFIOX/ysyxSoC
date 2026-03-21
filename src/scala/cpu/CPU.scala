package ysyx.cpu

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
import ysyx.core.sram._

class CPU(idBits: Int)(implicit p: Parameters)
    extends LazyModule
    with HasAXIParameter {

  // format: off
  val icacheNode = SRAMMasterNode( Seq( SRAMMasterPortParameters( masters = Seq( SRAMMasterParameters(name = "icache")))))
  val dcacheNode = SRAMMasterNode( Seq( SRAMMasterPortParameters( masters = Seq( SRAMMasterParameters(name = "dcache")))))
  val peripNode = SRAMMasterNode( Seq( SRAMMasterPortParameters( masters = Seq( SRAMMasterParameters(name = "perip")))))
  // format: on

  val masterNode = AXI4Xbar()
  val licache = LazyModule(new AXI4ICache)

  // masterNode := licache.node := icacheNode
  masterNode := SRAMToAXI4() := icacheNode
  masterNode := SRAMToAXI4() := dcacheNode
  masterNode := SRAMToAXI4() := peripNode

  lazy val module = new Impl
  class Impl extends LazyModuleImp(this) {
    // --- io ---
    val (icache, _) = icacheNode.out(0)
    val (dcache, _) = dcacheNode.out(0)
    val (perip, _) = peripNode.out(0)
    val interrupt = IO(Input(Bool()))
    val slave = IO(Flipped(AXI4Bundle(axiParams))) // used for chiplink

    // --- modules ---
    val core = Module(new NPCCore)

    // --- connect ---
    val probe = IO(chiselTypeOf(core.probe))
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

    licache.module.fence_i := core.fence_i

    // Interrupt is not used yet
    core.interrupt := interrupt
  }
}
