package ysyx

import chisel3._
import chisel3.util._

import org.chipsalliance.cde.config.Parameters
import freechips.rocketchip.amba._
import freechips.rocketchip.amba.axi4._
import freechips.rocketchip.amba.apb._
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.util._

case class AXI4ToAPBNode()(implicit valName: ValName) extends MixedAdapterNode(AXI4Imp, APBImp)(
  dFn = { mp =>
    APBMasterPortParameters(
      masters = mp.masters.map { m => APBMasterParameters(name = m.name, nodePath = m.nodePath) },
      requestFields = mp.requestFields.filter(!_.isInstanceOf[AMBAProtField]),
      responseKeys  = mp.responseKeys
    )
  },
  uFn = { sp =>
    val beatBytes = 4
    AXI4SlavePortParameters(
    slaves = sp.slaves.map { s =>
      val maxXfer = TransferSizes(1, beatBytes)
      require(beatBytes == 4) // only support 8-byte data AXI
      AXI4SlaveParameters(
        address       = s.address,
        resources     = s.resources,
        regionType    = s.regionType,
        executable    = s.executable,
        nodePath      = s.nodePath,
        supportsWrite = if (s.supportsWrite) TransferSizes(1, beatBytes) else TransferSizes.none,
        supportsRead  = if (s.supportsRead)  TransferSizes(1, beatBytes) else TransferSizes.none,
        interleavedId = Some(0))}, // never interleaves D beats
    beatBytes = beatBytes,
    responseFields = sp.responseFields,
    requestKeys    = sp.requestKeys.filter(_ != AMBAProt))
  }
)

class AXI4ToAPB(val aFlow: Boolean = true)(implicit p: Parameters) extends LazyModule {
  val node = AXI4ToAPBNode()

  lazy val module = new LazyModuleImp(this) {
    (node.in zip node.out) foreach { case ((in, edgeIn), (out, edgeOut)) =>
      // in: AXI4Bundle(master), out: APBBundle(slave)
      val (ar, r, aw, w, b) = (in.ar, in.r, in.aw, in.w, in.b)

      object State extends ChiselEnum {
        val idle, inflight, wait_rready_bready = Value
      }
      val state = RegInit(State.idle)
      val accept_read = (state === State.idle) && ar.valid /* valid 一旦拉高就不能撤下 */
      val accept_write = !accept_read && (state === State.idle) && aw.valid && w.valid
      val is_write = accept_write holdUnless (state === State.idle)
      switch (state) {
        is (State.idle)     { state := Mux(ar.valid || (aw.valid && w.valid), State.inflight, State.idle) }
        is (State.inflight) { state := Mux(out.pready, Mux(r.fire || b.fire, State.idle, State.wait_rready_bready), State.inflight) }
        is (State.wait_rready_bready) { state := Mux(r.fire || b.fire, State.idle, State.wait_rready_bready) }
      }

      // burst is not supported
      assert(!(ar.valid && ar.bits.len =/= 0.U))
      assert(!(aw.valid && aw.bits.len =/= 0.U))
      // size > 4 is not supported
      assert(!(ar.valid && ar.bits.size > "b10".U))
      assert(!(aw.valid && aw.bits.size > "b10".U))

      val rid_reg    = RegEnable(ar.bits.id, accept_read)
      val bid_reg    = RegEnable(aw.bits.id, accept_write)
      val araddr_reg = ar.bits.addr holdUnless accept_read
      val awaddr_reg = aw.bits.addr holdUnless accept_write
      val wdata_reg  =  w.bits.data holdUnless accept_write
      val wstrb_reg  =  w.bits.strb holdUnless accept_write

      out.psel    := (accept_read || accept_write) || out.penable
      out.penable := state === State.inflight
      out.pwrite  := is_write
      out.paddr   := Mux(is_write, awaddr_reg, araddr_reg)
      out.pprot   := APBParameters.PROT_DEFAULT
      out.pwdata  := wdata_reg
      out.pstrb   := Mux(is_write, wstrb_reg, 0.U)

      ar.ready := accept_read
      w.ready  := accept_write
      aw.ready := accept_write

      val resp = Mux(out.pslverr, AXI4Parameters.RESP_SLVERR, AXI4Parameters.RESP_OKAY)
      val resp_hold = resp holdUnless (state === State.inflight)
      r.valid  := !is_write && (((state === State.inflight) && out.pready) || (state === State.wait_rready_bready))
      r.bits.data := Fill(2, out.prdata holdUnless (state === State.inflight))
      r.bits.id   := rid_reg
      r.bits.resp := resp_hold
      r.bits.last := true.B

      b.valid  := is_write && (((state === State.inflight) && out.pready) || (state === State.wait_rready_bready))
      b.bits.resp := resp_hold
      b.bits.id   := bid_reg
    }
  }
}

object AXI4ToAPB {
  def apply(aFlow: Boolean = true)(implicit p: Parameters) = {
    val axi42apb = LazyModule(new AXI4ToAPB(aFlow))
    axi42apb.node
  }
}
