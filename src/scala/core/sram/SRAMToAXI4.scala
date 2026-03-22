package ysyx.core.sram

import chisel3._
import chisel3.util._

import org.chipsalliance.cde.config.Parameters
import freechips.rocketchip.amba.axi4._
import freechips.rocketchip.diplomacy._

case class SRAMToAXI4Node()(implicit valName: ValName)
    extends MixedAdapterNode(SRAMImp, AXI4Imp)(
      dFn = { mp =>
        AXI4MasterPortParameters(
          masters = mp.masters.map { m =>
            AXI4MasterParameters(
              name = m.name,
              id = IdRange(0, 1),
              nodePath = m.nodePath
            )
          }
        )
      },
      uFn = { sp =>
        SRAMSlavePortParameters(
          slaves = sp.slaves.map { s =>
            SRAMSlaveParameters(
              address = s.address,
              resources = s.resources,
              regionType = s.regionType,
              executable = s.executable,
              nodePath = s.nodePath,
              supportsRead = s.supportsRead.max > 0,
              supportsWrite = s.supportsWrite.max > 0
            )
          },
          beatBytes = sp.beatBytes
        )
      }
    )

class SRAMToAXI4Impl(
    id: Int,
    sramParams: SRAMBundleParameters,
    axiParams: AXI4BundleParameters
) extends Module {
  val io = IO(new Bundle {
    val in = Flipped(new SRAMBundle(sramParams))
    val out = new AXI4Bundle(axiParams)
  })

  val in = io.in
  val out = io.out

  object State extends ChiselEnum {
    val idle, readAddr, readData, write, writeResp = Value
  }
  val stateQ = RegInit(State.idle)

  // AW/W may be accepted on different cycles; track each independently
  val awDoneQ = RegInit(false.B)
  val wDoneQ = RegInit(false.B)

  // AR channel defaults
  out.ar.valid := false.B
  out.ar.bits.id := id.U
  out.ar.bits.addr := in.addr
  out.ar.bits.len := 0.U
  out.ar.bits.size := in.size
  out.ar.bits.burst := 1.U // INCR
  out.ar.bits.lock := 0.U
  out.ar.bits.cache := 0.U
  out.ar.bits.prot := 0.U
  out.ar.bits.qos := 0.U

  // AW channel defaults
  out.aw.valid := false.B
  out.aw.bits.id := id.U
  out.aw.bits.addr := in.addr
  out.aw.bits.len := 0.U
  out.aw.bits.size := in.size
  out.aw.bits.burst := 1.U
  out.aw.bits.lock := 0.U
  out.aw.bits.cache := 0.U
  out.aw.bits.prot := 0.U
  out.aw.bits.qos := 0.U

  // W channel defaults
  out.w.valid := false.B
  out.w.bits.data := in.wdata
  out.w.bits.strb := in.wstrb
  out.w.bits.last := true.B

  // R/B channel defaults
  out.r.ready := false.B
  out.b.ready := false.B

  // SRAM response defaults
  in.ack := false.B
  in.done := false.B
  in.rdata := out.r.bits.data

  switch(stateQ) {
    is(State.idle) {
      when(in.req) {
        stateQ := Mux(in.wen, State.write, State.readAddr)
      }
    }
    is(State.readAddr) {
      out.ar.valid := true.B
      when(out.ar.fire) {
        in.ack := true.B
        stateQ := State.readData
      }
    }
    is(State.readData) {
      out.r.ready := true.B
      when(out.r.fire) {
        in.done := true.B
        stateQ := State.idle
      }
    }
    is(State.write) {
      out.aw.valid := !awDoneQ
      out.w.valid := !wDoneQ
      when(out.aw.fire) { awDoneQ := true.B }
      when(out.w.fire) { wDoneQ := true.B }
      val bothDone = (awDoneQ || out.aw.fire) && (wDoneQ || out.w.fire)
      when(bothDone) {
        in.ack := true.B
        awDoneQ := false.B
        wDoneQ := false.B
        stateQ := State.writeResp
      }
    }
    is(State.writeResp) {
      out.b.ready := true.B
      when(out.b.fire) {
        in.done := true.B
        stateQ := State.idle
      }
    }
  }
}

class SRAMToAXI4(id: Int)(implicit p: Parameters) extends LazyModule {
  val node = SRAMToAXI4Node()
  lazy val module = new LazyModuleImp(this) {
    (node.in zip node.out) foreach { case ((in, edgeIn), (out, edgeOut)) =>
      val sramParams = edgeIn.bundle
      val axiParams = edgeOut.bundle
      val impl = Module(new SRAMToAXI4Impl(id, sramParams, axiParams))
      impl.io.in <> in
      out <> impl.io.out
    }
  }
}

object SRAMToAXI4 {
  def apply(id: Int)(implicit p: Parameters): SRAMToAXI4Node = {
    val sram2axi4 = LazyModule(new SRAMToAXI4(id))
    sram2axi4.node
  }
}
