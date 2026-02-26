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

// AXI4 Arbiter to merge icache and dcache into single master port
// Priority: dcache > icache (data access has higher priority)
class AXI4Arbiter extends Module {
  val io = IO(new Bundle {
    val icache = Flipped(AXI4Bundle(CPUAXI4BundleParameters()))
    val dcache = Flipped(AXI4Bundle(CPUAXI4BundleParameters()))
    val master = AXI4Bundle(CPUAXI4BundleParameters())
  })

  // Simple arbiter state machine
  object ArbState extends ChiselEnum {
    val idle, icache_ar, icache_r, dcache_ar, dcache_r, dcache_aw, dcache_w, dcache_b = Value
  }
  val state = RegInit(ArbState.idle)

  // Default: disconnect all
  io.icache.ar.ready := false.B
  io.icache.r.valid := false.B
  io.icache.r.bits := DontCare
  io.icache.aw.ready := false.B
  io.icache.w.ready := false.B
  io.icache.b.valid := false.B
  io.icache.b.bits := DontCare

  io.dcache.ar.ready := false.B
  io.dcache.r.valid := false.B
  io.dcache.r.bits := DontCare
  io.dcache.aw.ready := false.B
  io.dcache.w.ready := false.B
  io.dcache.b.valid := false.B
  io.dcache.b.bits := DontCare

  io.master.ar.valid := false.B
  io.master.ar.bits := DontCare
  io.master.r.ready := false.B
  io.master.aw.valid := false.B
  io.master.aw.bits := DontCare
  io.master.w.valid := false.B
  io.master.w.bits := DontCare
  io.master.b.ready := false.B

  switch(state) {
    is(ArbState.idle) {
      // Priority: dcache write > dcache read > icache read
      when(io.dcache.aw.valid) {
        state := ArbState.dcache_aw
      }.elsewhen(io.dcache.ar.valid) {
        state := ArbState.dcache_ar
      }.elsewhen(io.icache.ar.valid) {
        state := ArbState.icache_ar
      }
    }

    is(ArbState.icache_ar) {
      io.master.ar.valid := io.icache.ar.valid
      io.master.ar.bits := io.icache.ar.bits
      io.icache.ar.ready := io.master.ar.ready
      when(io.master.ar.fire) {
        state := ArbState.icache_r
      }
    }

    is(ArbState.icache_r) {
      io.master.r.ready := io.icache.r.ready
      io.icache.r.valid := io.master.r.valid
      io.icache.r.bits := io.master.r.bits
      when(io.master.r.fire && io.master.r.bits.last) {
        state := ArbState.idle
      }
    }

    is(ArbState.dcache_ar) {
      io.master.ar.valid := io.dcache.ar.valid
      io.master.ar.bits := io.dcache.ar.bits
      io.dcache.ar.ready := io.master.ar.ready
      when(io.master.ar.fire) {
        state := ArbState.dcache_r
      }
    }

    is(ArbState.dcache_r) {
      io.master.r.ready := io.dcache.r.ready
      io.dcache.r.valid := io.master.r.valid
      io.dcache.r.bits := io.master.r.bits
      when(io.master.r.fire && io.master.r.bits.last) {
        state := ArbState.idle
      }
    }

    is(ArbState.dcache_aw) {
      io.master.aw.valid := io.dcache.aw.valid
      io.master.aw.bits := io.dcache.aw.bits
      io.dcache.aw.ready := io.master.aw.ready
      when(io.master.aw.fire) {
        state := ArbState.dcache_w
      }
    }

    is(ArbState.dcache_w) {
      io.master.w.valid := io.dcache.w.valid
      io.master.w.bits := io.dcache.w.bits
      io.dcache.w.ready := io.master.w.ready
      when(io.master.w.fire && io.master.w.bits.last) {
        state := ArbState.dcache_b
      }
    }

    is(ArbState.dcache_b) {
      io.master.b.ready := io.dcache.b.ready
      io.dcache.b.valid := io.master.b.valid
      io.dcache.b.bits := io.master.b.bits
      when(io.master.b.fire) {
        state := ArbState.idle
      }
    }
  }
}

class CPU(idBits: Int)(implicit p: Parameters) extends LazyModule {
  val masterNode = AXI4MasterNode(p(ExtIn).map(params =>
    AXI4MasterPortParameters(
      masters = Seq(AXI4MasterParameters(
        name = "cpu",
        id   = IdRange(0, 1 << idBits))))).toSeq)
  lazy val module = new Impl
  class Impl extends LazyModuleImp(this) {
    val (master, _) = masterNode.out(0)
    val interrupt = IO(Input(Bool()))
    val slave = IO(Flipped(AXI4Bundle(CPUAXI4BundleParameters())))

    val step  = IO(Input(Bool()))
    val probe = IO(Output(Probe(new DebugBundle)))

    val cpu = Module(new NPCCore)

    cpu.io.step := step
    define(probe, cpu.io.probe)

    // Instantiate arbiter to merge icache and dcache
    val arbiter = Module(new AXI4Arbiter)
    arbiter.io.icache <> cpu.io.icache
    arbiter.io.dcache <> cpu.io.dcache
    master <> arbiter.io.master

    // Slave interface is not used by NPCCore, tie off
    slave.ar.ready := false.B
    slave.aw.ready := false.B
    slave.w.ready := false.B
    slave.r.valid := false.B
    slave.r.bits := DontCare
    slave.b.valid := false.B
    slave.b.bits := DontCare

    // Interrupt is not used yet
    locally { val _ = interrupt }
  }
}
