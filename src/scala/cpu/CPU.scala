package ysyx.cpu

import chisel3._
import chisel3.util._

import org.chipsalliance.cde.config.Parameters
import freechips.rocketchip.subsystem._
import freechips.rocketchip.amba.axi4._
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.util._

import ysyx.core.NPCCore // TODO: spike or core
import ysyx.core.DebugBundle
import ysyx.cpu.cache.{AXI4DCache, AXI4ICache}
import ysyx.core.common.HasAXIParameter
import ysyx.core.sram._

class CPU(implicit p: Parameters)
    extends LazyModule
    with HasAXIParameter {

  // format: off
  val icacheNode = SRAMMasterNode( Seq( SRAMMasterPortParameters( masters = Seq( SRAMMasterParameters(name = "icache")))))
  val dcacheNode = SRAMMasterNode( Seq( SRAMMasterPortParameters( masters = Seq( SRAMMasterParameters(name = "dcache")))))
  val peripNode = SRAMMasterNode( Seq( SRAMMasterPortParameters( masters = Seq( SRAMMasterParameters(name = "perip")))))
  val iptwNode = SRAMMasterNode( Seq( SRAMMasterPortParameters( masters = Seq( SRAMMasterParameters(name = "iptw")))))
  val dptwNode = SRAMMasterNode( Seq( SRAMMasterPortParameters( masters = Seq( SRAMMasterParameters(name = "dptw")))))
  // format: on

  val masterNode = AXI4Xbar()
  val licache = LazyModule(new AXI4ICache(0))
  val ldcache = LazyModule(new AXI4DCache(1))

  masterNode := licache.node := icacheNode
  masterNode := ldcache.node := dcacheNode
  masterNode := SRAMToAXI4(2) := peripNode
  masterNode := SRAMToAXI4(3) := iptwNode
  masterNode := SRAMToAXI4(4) := dptwNode

  lazy val module = new Impl
  class Impl extends LazyModuleImp(this) {
    // --- io ---
    val (icache, _) = icacheNode.out(0)
    val (dcache, _) = dcacheNode.out(0)
    val (perip, _) = peripNode.out(0)
    val (iptw, _) = iptwNode.out(0)
    val (dptw, _) = dptwNode.out(0)
    val ext_irq = IO(Input(Bool()))
    val mtime_in = IO(Input(UInt(64.W)))

    // --- modules ---
    val core = Module(new NPCCore)

    // --- connect ---
    val probe = IO(chiselTypeOf(core.probe))
    probe := core.probe

    icache <> core.icache
    dcache <> core.dcache
    perip <> core.perip
    iptw <> core.iptw_port
    dptw <> core.dptw_port

    licache.module.fence_i := core.fence_i
    ldcache.module.fence_i := core.fence_i
    ldcache.module.sfence_vma := core.sfence_vma

    core.ext_irq := ext_irq
    core.mtime_in := mtime_in
  }
}
