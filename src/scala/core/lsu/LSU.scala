package ysyx.core.lsu

import chisel3._
import chisel3.util._

import freechips.rocketchip.amba.axi4._
import freechips.rocketchip.util._

import ysyx.core.common._
import ysyx.core.backend._
import ysyx.SoCConfig
import ysyx.util.ReqAckDone

object MemUExceptionType extends ChiselEnum {
  val mem_LOAD_ADDRESS_MISALIGNED, mem_LOAD_ACCESS_FAULT,
      mem_STORE_ADDRESS_MISALIGNED, mem_STORE_ACCESS_FAULT, mem_LOAD_PAGE_FAULT,
      mem_STORE_PAGE_FAULT = Value
}

object SignExt {
  def apply(data: UInt, width: Int = 32): UInt = {
    val signBit = data(data.getWidth - 1)
    Cat(Fill(width - data.getWidth, signBit), data)
  }
}

object ZeroExt {
  def apply(data: UInt, width: Int = 32): UInt = {
    Cat(0.U((width - data.getWidth).W), data)
  }
}

// signal shdirections are from the slave's point-of-view
class SRAMBundle(axiParams: AXI4BundleParameters) extends Bundle {
  private val sizeBits = axiParams.sizeBits
  private val dataBits = axiParams.dataBits
  private val strbBits = axiParams.dataBits / 8
  private val addrBits = axiParams.addrBits
  private val respBits = axiParams.respBits
  val wr = Output(Bool()) // 0: read; 1: write
  val size = Output(UInt(sizeBits.W)) // 2^size. 0:1; 1:2; 2:4
  val addr = Output(UInt(addrBits.W))
  val wstrb = Output(UInt(strbBits.W))
  val wdata = Output(UInt(dataBits.W))
  val resp = Input(UInt(respBits.W))
  val rdata = Input(UInt(dataBits.W))
}

class LoadUnit(axiParams: AXI4BundleParameters, id: Int) extends Module {
  val in = IO(Flipped(ReqAckDone(new SRAMBundle(axiParams))))
  val ar = IO(Irrevocable(new AXI4BundleAR(axiParams)))
  val r = IO(Flipped(Irrevocable(new AXI4BundleR(axiParams))))

  // ar
  ar.bits.id := id.U
  ar.bits.addr := in.bits.addr
  ar.bits.len := 0.U
  ar.bits.size := in.bits.size
  ar.bits.burst := 1.U
  ar.bits.lock := 0.U
  ar.bits.cache := 0.U
  ar.bits.prot := 0.U
  ar.bits.qos := 0.U

  // in
  in.bits.rdata := r.bits.data holdUnless r.fire
  in.bits.resp := r.bits.resp holdUnless r.fire
  in.ack := ar.fire
  in.done := r.fire

  object State extends ChiselEnum {
    val idle, ar_wait, r_wait = Value
  }
  val stateQ = RegInit(State.idle)

  ar.valid := in.req && (stateQ === State.idle || stateQ === State.ar_wait)
  r.ready := (stateQ === State.r_wait)

  switch(stateQ) {
    is(State.idle) {
      when(in.req) {
        when(ar.fire) {
          stateQ := State.r_wait
        }.otherwise {
          stateQ := State.ar_wait
        }
      }
    }
    is(State.ar_wait) {
      when(ar.fire) {
        stateQ := State.r_wait
      }
    }
    is(State.r_wait) {
      when(r.fire) {
        stateQ := State.idle
      }
    }
  }

}

// for write, addr_ok means: sucess of reading Addr, Data and size
class StoreUnit(axiParams: AXI4BundleParameters, id: Int) extends Module {
  val in = IO(Flipped(ReqAckDone(new SRAMBundle(axiParams))))
  val aw = IO(Irrevocable(new AXI4BundleAW(axiParams)))
  val w = IO(Irrevocable(new AXI4BundleW(axiParams)))
  val b = IO(Flipped(Irrevocable(new AXI4BundleB(axiParams))))

  // aw
  aw.bits.id := id.U
  aw.bits.addr := in.bits.addr
  aw.bits.len := 0.U
  aw.bits.size := in.bits.size
  aw.bits.burst := 1.U
  aw.bits.lock := 0.U
  aw.bits.cache := 0.U // TODO:
  aw.bits.prot := 0.U
  aw.bits.qos := 0.U

  // w
  w.bits.data := in.bits.wdata
  w.bits.strb := in.bits.wstrb
  w.bits.last := true.B

  // hardcode
  in.bits.rdata := 0.U

  // in
  in.bits.resp := b.bits.resp holdUnless b.fire
  in.done := b.fire

  object State extends ChiselEnum {
    //   0      1          2
    val idle, aw_w_wait, b_wait = Value
  }
  val stateQ = RegInit(State.idle)

  // state registers
  val aw_sent_q = RegInit(false.B)
  val w_sent_q = RegInit(false.B)
  val aw_done = aw_sent_q || aw.fire
  val w_done = w_sent_q || w.fire

  // defaults
  in.ack := (stateQ === State.aw_w_wait) && aw_done && w_done
  aw.valid := in.req && !aw_sent_q && (stateQ =/= State.b_wait)
  w.valid := in.req && !w_sent_q && (stateQ =/= State.b_wait)
  b.ready := (stateQ === State.b_wait)

  switch(stateQ) {
    is(State.idle) {
      aw_sent_q := false.B
      w_sent_q := false.B
      when(in.req) {
        when(aw.fire) { aw_sent_q := true.B }
        when(w.fire) { w_sent_q := true.B }
        stateQ := State.aw_w_wait
      }
    }
    is(State.aw_w_wait) {
      when(aw.fire) { aw_sent_q := true.B }
      when(w.fire) { w_sent_q := true.B }
      when(aw_done && w_done) { stateQ := State.b_wait }
    }
    is(State.b_wait) {
      when(b.fire) {
        stateQ := State.idle
        aw_sent_q := false.B
        w_sent_q := false.B
      }
    }
  }

}

// from the view of Rob -> LSU
class MemLsuInput extends MemInfoBundle {
  val is_mmio = Bool()
  val result = Input(UInt(dataBits.W))
  val result_valid = Input(Bool()) // for rob entry's rd valid
  // val mcause = Input(UInt(dataBits.W))
  // val has_except = Input(Bool())
}

class LSU extends LateExecUnit(new MemLsuInput) {
  val dcache = IO(AXI4Bundle(axiParams))
  val perip = IO(AXI4Bundle(axiParams))

  // ==========================================================
  // dcache channel — LU + SU
  // ==========================================================
  val cache_load = Module(new LoadUnit(axiParams, 1))
  val cache_store = Module(new StoreUnit(axiParams, 2))

  cache_load.ar <> dcache.ar
  cache_load.r <> dcache.r
  cache_store.aw <> dcache.aw
  cache_store.w <> dcache.w
  cache_store.b <> dcache.b

  // ==========================================================
  // perip channel — LU + SU
  // ==========================================================
  val perip_load = Module(new LoadUnit(axiParams, 3))
  val perip_store = Module(new StoreUnit(axiParams, 4))

  perip_load.ar <> perip.ar
  perip_load.r <> perip.r
  perip_store.aw <> perip.aw
  perip_store.w <> perip.w
  perip_store.b <> perip.b

  // ==========================================================
  // Common: store wstrb / wdata formatting (4B aligned for both channels)
  // ==========================================================
  val ctrl = late.bits

  // aligned
  val aligned_addr = Cat(ctrl.addr(addrBits - 1, 2), 0.U(2.W))

  val store_wstrb = MuxLookup(ctrl.size, "b1111".U)(
    Seq(
      0.U -> (1.U(4.W) << ctrl.addr(1, 0)),
      1.U -> (3.U(4.W) << (ctrl.addr(1, 0) & "b10".U)),
      2.U -> "b1111".U(4.W)
    )
  )
  val store_wdata = MuxLookup(ctrl.size, ctrl.wdata)(
    Seq(
      0.U -> Fill(4, ctrl.wdata(7, 0)),
      1.U -> Fill(2, ctrl.wdata(15, 0)),
      2.U -> ctrl.wdata
    )
  )

  // ==========================================================
  // Load data extraction
  // ==========================================================

  // Cache: returns full word, need byte lane selection by addr(1,0)
  val cache_raw = cache_load.in.bits.rdata
  val cache_byte = MuxLookup(ctrl.addr(1, 0), 0.U)(
    Seq(
      0.U -> cache_raw(7, 0),
      1.U -> cache_raw(15, 8),
      2.U -> cache_raw(23, 16),
      3.U -> cache_raw(31, 24)
    )
  )
  val cache_half = Mux(ctrl.addr(1), cache_raw(31, 16), cache_raw(15, 0))
  val cache_load_final = MuxLookup(ctrl.size, cache_raw)(
    Seq(
      0.U -> Mux(ctrl.sign_ext, SignExt(cache_byte, 32), ZeroExt(cache_byte, 32)), // lb, lbu
      1.U -> Mux(ctrl.sign_ext, SignExt(cache_half, 32), ZeroExt(cache_half, 32)), // lh, lhu
      2.U -> cache_raw // lw
    )
  )

  // MMIO: narrow transfer, data already in lower bits — no lane selection
  val mmio_raw = perip_load.in.bits.rdata
  val mmio_load_final = MuxLookup(ctrl.size, mmio_raw)(
    Seq(
      0.U -> Mux(ctrl.sign_ext, SignExt(mmio_raw(7, 0), 32), ZeroExt(mmio_raw(7, 0), 32)),
      1.U -> Mux(ctrl.sign_ext, SignExt(mmio_raw(15, 0), 32), ZeroExt(mmio_raw(15, 0), 32)),
      2.U -> mmio_raw
    )
  )

  val load_final = Mux(ctrl.is_mmio, mmio_load_final, cache_load_final)

  // ==========================================================
  // dcache LoadUnit defaults — 4B aligned, size=2
  // ==========================================================
  cache_load.in.req := false.B
  cache_load.in.bits.wr := false.B
  cache_load.in.bits.size := 2.U
  cache_load.in.bits.addr := aligned_addr
  cache_load.in.bits.wstrb := 0.U // disable
  cache_load.in.bits.wdata := 0.U

  // dcache StoreUnit defaults — 4B aligned, size=2
  cache_store.in.req := false.B
  cache_store.in.bits.wr := true.B
  cache_store.in.bits.size := 2.U
  cache_store.in.bits.addr := aligned_addr
  cache_store.in.bits.wstrb := store_wstrb
  cache_store.in.bits.wdata := store_wdata

  // ==========================================================
  // perip LoadUnit defaults — raw addr, raw size (narrow transfer)
  // ==========================================================
  perip_load.in.req := false.B
  perip_load.in.bits.wr := false.B
  perip_load.in.bits.size := ctrl.size
  perip_load.in.bits.addr := ctrl.addr
  perip_load.in.bits.wstrb := 0.U
  perip_load.in.bits.wdata := 0.U

  // perip StoreUnit defaults — 4B aligned, size=2
  perip_store.in.req := false.B
  perip_store.in.bits.wr := true.B
  perip_store.in.bits.size := 2.U
  perip_store.in.bits.addr := aligned_addr
  perip_store.in.bits.wstrb := store_wstrb
  perip_store.in.bits.wdata := store_wdata

  // ==========================================================
  // LateExecIO defaults
  // ==========================================================
  late.done := false.B
  late.bits.result := load_final
  late.bits.result_valid := ctrl.r_en

  // ==========================================================
  // Internal state machine
  // ==========================================================
  object LSUState extends ChiselEnum {
    val idle, lsu_req, lsu_wait = Value
  }
  val stateQ = RegInit(LSUState.idle)

  switch(stateQ) {
    is(LSUState.idle) {
      when(late.req) {
        when(ctrl.r_en) {
          when(ctrl.is_mmio) { perip_load.in.req := true.B }
            .otherwise { cache_load.in.req := true.B }
        }.elsewhen(ctrl.w_en) {
          when(ctrl.is_mmio) { perip_store.in.req := true.B }
            .otherwise { cache_store.in.req := true.B }
        }
        stateQ := LSUState.lsu_req
      }
    }
    is(LSUState.lsu_req) {
      when(ctrl.r_en) {
        when(ctrl.is_mmio) {
          perip_load.in.req := true.B
          when(perip_load.in.ack) { stateQ := LSUState.lsu_wait }
        }.otherwise {
          cache_load.in.req := true.B
          when(cache_load.in.ack) { stateQ := LSUState.lsu_wait }
        }
      }.elsewhen(ctrl.w_en) {
        when(ctrl.is_mmio) {
          perip_store.in.req := true.B
          when(perip_store.in.ack) { stateQ := LSUState.lsu_wait }
        }.otherwise {
          cache_store.in.req := true.B
          when(cache_store.in.ack) { stateQ := LSUState.lsu_wait }
        }
      }
    }
    is(LSUState.lsu_wait) {
      when(ctrl.r_en) {
        val data_ok = Mux(ctrl.is_mmio, perip_load.in.done, cache_load.in.done)
        when(data_ok) {
          late.done := true.B
          stateQ := LSUState.idle
        }
      }.elsewhen(ctrl.w_en) {
        val data_ok = Mux(ctrl.is_mmio, perip_store.in.done, cache_store.in.done)
        when(data_ok) {
          late.done := true.B
          late.bits.result_valid := false.B
          stateQ := LSUState.idle
        }
      }
    }
  }
}
