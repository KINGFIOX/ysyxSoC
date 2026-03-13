package ysyx.core.component

import chisel3._
import chisel3.util._

import freechips.rocketchip.amba.axi4._
import freechips.rocketchip.util._

import ysyx.core.common._
import ysyx.SoCConfig

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
  val req = Output(Bool()) // input stable when req asserted
  val wr = Output(Bool()) // 0: read; 1: write
  val size = Output(UInt(sizeBits.W)) // 2^size. 0:1; 1:2; 2:4
  val addr = Output(UInt(addrBits.W))
  val wstrb = Output(UInt(strbBits.W))
  val wdata = Output(UInt(dataBits.W))
  val addr_ok = Input(Bool())
  val data_ok = Input(Bool()) // read valid; write done
  val resp = Input(UInt(respBits.W))
  val rdata = Input(UInt(dataBits.W))
}

class LoadUnit(axiParams: AXI4BundleParameters, id: Int) extends Module {
  val in = IO(Flipped(new SRAMBundle(axiParams)))
  val ar = IO(Irrevocable(new AXI4BundleAR(axiParams)))
  val r = IO(Flipped(Irrevocable(new AXI4BundleR(axiParams))))

  // ar
  ar.bits.id := id.U
  ar.bits.addr := in.addr
  ar.bits.len := 0.U
  ar.bits.size := in.size
  ar.bits.burst := 1.U
  ar.bits.lock := 0.U
  ar.bits.cache := 0.U
  ar.bits.prot := 0.U
  ar.bits.qos := 0.U

  // in
  in.rdata := r.bits.data holdUnless r.fire
  in.resp := r.bits.resp holdUnless r.fire
  in.addr_ok := ar.fire
  in.data_ok := r.fire

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
  val in = IO(Flipped(new SRAMBundle(axiParams)))
  val aw = IO(Irrevocable(new AXI4BundleAW(axiParams)))
  val w = IO(Irrevocable(new AXI4BundleW(axiParams)))
  val b = IO(Flipped(Irrevocable(new AXI4BundleB(axiParams))))

  // aw
  aw.bits.id := id.U
  aw.bits.addr := in.addr
  aw.bits.len := 0.U
  aw.bits.size := in.size
  aw.bits.burst := 1.U
  aw.bits.lock := 0.U
  aw.bits.cache := 0.U // TODO:
  aw.bits.prot := 0.U
  aw.bits.qos := 0.U

  // w
  w.bits.data := in.wdata
  w.bits.strb := in.wstrb
  w.bits.last := true.B

  // hardcode
  in.rdata := 0.U

  // in
  in.resp := b.bits.resp holdUnless b.fire
  in.data_ok := b.fire

  object State extends ChiselEnum {
    val idle, aw_w_wait, b_wait = Value
  }
  val stateQ = RegInit(State.idle)

  // state registers
  val aw_sent_q = RegInit(false.B)
  val w_sent_q = RegInit(false.B)
  val aw_done = aw_sent_q || aw.fire
  val w_done = w_sent_q || w.fire

  // defaults
  in.addr_ok := (stateQ =/= State.b_wait) && aw_done && w_done
  aw.valid := in.req && !aw_sent_q
  w.valid := in.req && !w_sent_q
  b.ready := (stateQ === State.b_wait)

  switch(stateQ) {
    is(State.idle) {
      aw_sent_q := false.B
      w_sent_q := false.B
      when(in.req) {
        when(aw.fire) { aw_sent_q := true.B }
        when(w.fire) { w_sent_q := true.B }
        stateQ := Mux(aw_done && w_done, State.b_wait, State.aw_w_wait)
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
      }
    }
  }

}

class LSU extends NPCModule {
  val dcache = IO(AXI4Bundle(axiParams))

  val io = IO(new Bundle {
    val late = new LateExecIO
    val addr = Input(UInt(addrBits.W))
    val size = Input(UInt(axiParams.sizeBits.W))
    val sign_ext = Input(Bool())
    val r_en = Input(Bool())
    val w_en = Input(Bool())
    val wdata = Input(UInt(dataBits.W))
  })

  val cache_load = Module(new LoadUnit(axiParams, 1))
  val cache_store = Module(new StoreUnit(axiParams, 2))

  cache_load.ar <> dcache.ar
  cache_load.r <> dcache.r
  cache_store.aw <> dcache.aw
  cache_store.w <> dcache.w
  cache_store.b <> dcache.b

  // Store wstrb / wdata formatting
  val store_wstrb = MuxLookup(io.size, "b1111".U)(
    Seq(
      0.U -> (1.U(4.W) << io.addr(1, 0)),
      1.U -> (3.U(4.W) << (io.addr(1, 0) & "b10".U)),
      2.U -> "b1111".U(4.W)
    )
  )
  val store_wdata = MuxLookup(io.size, io.wdata)(
    Seq(
      0.U -> Fill(4, io.wdata(7, 0)),
      1.U -> Fill(2, io.wdata(15, 0)),
      2.U -> io.wdata
    )
  )

  // Load data extraction
  val load_raw = cache_load.in.rdata
  val load_byte = MuxLookup(io.addr(1, 0), load_raw(7, 0))(
    Seq(
      0.U -> load_raw(7, 0),
      1.U -> load_raw(15, 8),
      2.U -> load_raw(23, 16),
      3.U -> load_raw(31, 24)
    )
  )
  val load_half = Mux(io.addr(1), load_raw(31, 16), load_raw(15, 0))
  val load_final = MuxLookup(io.size, load_raw)(
    Seq(
      0.U -> Mux(io.sign_ext, SignExt(load_byte, 32), ZeroExt(load_byte, 32)),
      1.U -> Mux(io.sign_ext, SignExt(load_half, 32), ZeroExt(load_half, 32)),
      2.U -> load_raw
    )
  )

  // LoadUnit defaults
  cache_load.in.req := false.B
  cache_load.in.wr := false.B
  cache_load.in.size := io.size
  cache_load.in.addr := io.addr
  cache_load.in.wstrb := 0.U
  cache_load.in.wdata := 0.U

  // StoreUnit defaults
  cache_store.in.req := false.B
  cache_store.in.wr := true.B
  cache_store.in.size := io.size
  cache_store.in.addr := io.addr
  cache_store.in.wstrb := store_wstrb
  cache_store.in.wdata := store_wdata

  // LateExecIO defaults
  io.late.done := false.B
  io.late.result := load_final
  io.late.result_valid := io.r_en

  // Internal state machine
  object LSUState extends ChiselEnum {
    val idle, lsu_req, lsu_wait = Value
  }
  val stateQ = RegInit(LSUState.idle)

  switch(stateQ) {
    is(LSUState.idle) {
      when(io.late.req) {
        when(io.r_en) {
          cache_load.in.req := true.B
        }.elsewhen(io.w_en) {
          cache_store.in.req := true.B
        }
        stateQ := LSUState.lsu_req
      }
    }
    is(LSUState.lsu_req) {
      when(io.r_en) {
        cache_load.in.req := true.B
        when(cache_load.in.addr_ok) {
          stateQ := LSUState.lsu_wait
        }
      }.elsewhen(io.w_en) {
        cache_store.in.req := true.B
        when(cache_store.in.addr_ok) {
          stateQ := LSUState.lsu_wait
        }
      }
    }
    is(LSUState.lsu_wait) {
      when(io.r_en && cache_load.in.data_ok) {
        io.late.done := true.B
        stateQ := LSUState.idle
      }.elsewhen(io.w_en && cache_store.in.data_ok) {
        io.late.done := true.B
        io.late.result_valid := false.B
        stateQ := LSUState.idle
      }
    }
  }
}
