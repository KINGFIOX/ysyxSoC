package ysyx.amba

import chisel3._
import chisel3.util._

import org.chipsalliance.cde.config.Parameters
import freechips.rocketchip.amba._
import freechips.rocketchip.amba.axi4._
import freechips.rocketchip.amba.apb._
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.util._

// ============================================================================
// AXI4full to APB Converter (64-bit AXI data, 32-bit APB data)
// ============================================================================
//
// For size <= 2 (<=4 bytes): single APB transaction
// For size == 3 (8 bytes): two APB transactions (lo then hi 4 bytes)

case class AXI4ToAPBNode()(implicit valName: ValName) extends MixedAdapterNode(AXI4Imp, APBImp)(
  dFn = { mp => // down
    APBMasterPortParameters(
      masters = mp.masters.map { m => APBMasterParameters(name = m.name, nodePath = m.nodePath) },
      requestFields = mp.requestFields.filter(!_.isInstanceOf[AMBAProtField]),
      responseKeys  = mp.responseKeys
    )
  },
  uFn = { sp => // up
    val beatBytes = 8
    AXI4SlavePortParameters(
    slaves = sp.slaves.map { s =>
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

class AXI4ToAPB()(implicit p: Parameters) extends LazyModule {
  val node = AXI4ToAPBNode()

  lazy val module = new LazyModuleImp(this) {
    (node.in zip node.out) foreach { case ((in, edgeIn), (out, edgeOut)) =>
      val (ar, r, aw, w, b) = (in.ar, in.r, in.aw, in.w, in.b)

      object State extends ChiselEnum {
        val idle, inflight_lo, hi_setup, inflight_hi, wait_rready_bready = Value
      }
      val stateQ = RegInit(State.idle)
      val accept_read = (stateQ === State.idle) && ar.valid
      val accept_write = !accept_read && (stateQ === State.idle) && aw.valid && w.valid
      val is_write = accept_write holdUnless (stateQ === State.idle)
      val is_wide = RegInit(false.B)

      assert(!(ar.valid && ar.bits.len =/= 0.U))
      assert(!(aw.valid && aw.bits.len =/= 0.U))

      when(accept_read)  { is_wide := ar.bits.size === 3.U }
      when(accept_write) { is_wide := aw.bits.size === 3.U }

      val rid_reg    = RegEnable(ar.bits.id, accept_read)
      val bid_reg    = RegEnable(aw.bits.id, accept_write)
      val araddr_reg = ar.bits.addr holdUnless accept_read
      val awaddr_reg = aw.bits.addr holdUnless accept_write
      val wdata_reg  =  w.bits.data holdUnless accept_write
      val wstrb_reg  =  w.bits.strb holdUnless accept_write

      val lo_rdata = Reg(UInt(32.W))

      // APB signals
      val in_access = stateQ === State.inflight_lo || stateQ === State.inflight_hi
      out.psel    := (accept_read || accept_write) || in_access || stateQ === State.hi_setup
      out.penable := in_access
      out.pwrite  := is_write
      out.pprot   := APBParameters.PROT_DEFAULT

      val base_addr = Mux(is_write, awaddr_reg, araddr_reg)
      val use_hi = stateQ === State.hi_setup || stateQ === State.inflight_hi
      out.paddr  := Mux(use_hi, base_addr + 4.U, base_addr)
      out.pwdata := Mux(use_hi, wdata_reg(63, 32), wdata_reg(31, 0))
      out.pstrb  := Mux(is_write, Mux(use_hi, wstrb_reg(7, 4), wstrb_reg(3, 0)), 0.U)

      ar.ready := accept_read
      w.ready  := accept_write
      aw.ready := accept_write

      val resp_lo = Reg(UInt(AXI4Parameters.respBits.W))
      val resp_cur = Mux(out.pslverr, AXI4Parameters.RESP_SLVERR, AXI4Parameters.RESP_OKAY)
      val final_resp = Mux(is_wide, resp_lo | resp_cur, resp_cur)
      val resp_hold = RegInit(0.U(AXI4Parameters.respBits.W))

      val done_lo = stateQ === State.inflight_lo && out.pready
      val done_hi = stateQ === State.inflight_hi && out.pready
      val txn_done = Mux(is_wide, done_hi, done_lo)
      val responding = txn_done || stateQ === State.wait_rready_bready

      r.valid     := !is_write && responding
      r.bits.id   := rid_reg
      r.bits.resp := resp_hold
      r.bits.last := true.B
      r.bits.data := DontCare

      val hi_rdata = out.prdata holdUnless txn_done
      r.bits.data := Mux(is_wide, Cat(hi_rdata, lo_rdata), Fill(2, hi_rdata))

      b.valid     := is_write && responding
      b.bits.resp := resp_hold
      b.bits.id   := bid_reg

      switch(stateQ) {
        is(State.idle) {
          when(ar.valid || (aw.valid && w.valid)) { stateQ := State.inflight_lo }
        }
        is(State.inflight_lo) {
          when(out.pready) {
            lo_rdata := out.prdata
            resp_lo := resp_cur
            when(is_wide) {
              stateQ := State.hi_setup
            }.otherwise {
              resp_hold := resp_cur
              stateQ := Mux(r.fire || b.fire, State.idle, State.wait_rready_bready)
            }
          }
        }
        is(State.hi_setup) {
          stateQ := State.inflight_hi
        }
        is(State.inflight_hi) {
          when(out.pready) {
            resp_hold := final_resp
            stateQ := Mux(r.fire || b.fire, State.idle, State.wait_rready_bready)
          }
        }
        is(State.wait_rready_bready) {
          stateQ := Mux(r.fire || b.fire, State.idle, State.wait_rready_bready)
        }
      }
    }
  }
}

object AXI4ToAPB {
  def apply()(implicit p: Parameters) : AXI4ToAPBNode = {
    val axi42apb = LazyModule(new AXI4ToAPB)
    axi42apb.node
  }
}
