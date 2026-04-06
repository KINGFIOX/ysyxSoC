package ysyx.core.lsu

import chisel3._
import chisel3.util._

import freechips.rocketchip.amba.axi4._
import freechips.rocketchip.util._

import ysyx.core.backend._
import ysyx.core.common._
import ysyx.core.sram._
import ysyx.SoCConfig

object MemUExceptionType extends ChiselEnum {
  val mem_LOAD_ADDRESS_MISALIGNED, mem_LOAD_ACCESS_FAULT,
      mem_STORE_ADDRESS_MISALIGNED, mem_STORE_ACCESS_FAULT, mem_LOAD_PAGE_FAULT,
      mem_STORE_PAGE_FAULT = Value
}

object SignExt {
  def apply(data: UInt, width: Int = 64): UInt = {
    val signBit = data(data.getWidth - 1)
    Cat(Fill(width - data.getWidth, signBit), data)
  }
}

object ZeroExt {
  def apply(data: UInt, width: Int = 64): UInt = {
    Cat(0.U((width - data.getWidth).W), data)
  }
}

// from the view of Rob -> LSU
class MemLate extends MemInfoBundle {
  val addr = UInt(addrBits.W)
  val wdata = UInt(dataBits.W)
  val is_mmio = Bool()
  val result = Input(UInt(dataBits.W))
  val rd_wen = Input(Bool())
}

class LSU extends LateExecUnit(new MemLate) {
  val dcache = IO(SRAMBundle(sramParams))
  val perip = IO(SRAMBundle(sramParams))

  val ctrl = late.bits
  val aligned_addr = Cat(ctrl.addr(addrBits - 1, dataBytesBits), 0.U(dataBytesBits.W))

  // ==========================================================
  // Store wstrb / wdata formatting (8B aligned for both channels)
  // ==========================================================
  val store_wstrb = MuxLookup(ctrl.size, "b11111111".U)(
    Seq(
      0.U -> (1.U(dataBytes.W) << ctrl.addr(dataBytesBits - 1, 0)),
      1.U -> (3.U(dataBytes.W) << Cat(ctrl.addr(dataBytesBits - 1, 1), 0.U(1.W))),
      2.U -> ("b1111".U(dataBytes.W) << Cat(ctrl.addr(dataBytesBits - 1, 2), 0.U(2.W))),
      3.U -> "b11111111".U(dataBytes.W)
    )
  )
  val store_wdata = MuxLookup(ctrl.size, ctrl.wdata)(
    Seq(
      0.U -> Fill(dataBytes, ctrl.wdata(7, 0)),
      1.U -> Fill(dataBytes / 2, ctrl.wdata(15, 0)),
      2.U -> Fill(dataBytes / 4, ctrl.wdata(31, 0)),
      3.U -> ctrl.wdata
    )
  )

  // ==========================================================
  // Load data extraction
  // ==========================================================

  // Cache: full doubleword returned, byte lane selection by addr(2,0)
  val cache_raw = dcache.rdata
  val byte_off = ctrl.addr(dataBytesBits - 1, 0)
  val cache_shifted = cache_raw >> (byte_off << 3)
  val cache_byte = cache_shifted(7, 0)
  val cache_half = cache_shifted(15, 0)
  val cache_word = cache_shifted(31, 0)
  val cache_load_final = MuxLookup(ctrl.size, cache_raw)(
    Seq(
      0.U -> Mux(ctrl.sign_ext, SignExt(cache_byte), ZeroExt(cache_byte)),
      1.U -> Mux(ctrl.sign_ext, SignExt(cache_half), ZeroExt(cache_half)),
      2.U -> Mux(ctrl.sign_ext, SignExt(cache_word), ZeroExt(cache_word)),
      3.U -> cache_raw
    )
  )

  // MMIO: narrow transfer, data already in lower bits
  val mmio_raw = perip.rdata
  val mmio_load_final = MuxLookup(ctrl.size, mmio_raw)(
    Seq(
      0.U -> Mux(ctrl.sign_ext, SignExt(mmio_raw(7, 0)), ZeroExt(mmio_raw(7, 0))),
      1.U -> Mux(ctrl.sign_ext, SignExt(mmio_raw(15, 0)), ZeroExt(mmio_raw(15, 0))),
      2.U -> Mux(ctrl.sign_ext, SignExt(mmio_raw(31, 0)), ZeroExt(mmio_raw(31, 0))),
      3.U -> mmio_raw
    )
  )

  val load_final = Mux(ctrl.is_mmio, mmio_load_final, cache_load_final)

  // ==========================================================
  // dcache defaults — 8B aligned, size=3 (load & store share the port)
  // ==========================================================
  dcache.req := false.B
  dcache.wen := ctrl.w_en
  dcache.size := dataBytesBits.U
  dcache.addr := aligned_addr(busAddrBits - 1, 0)
  dcache.wstrb := store_wstrb
  dcache.wdata := store_wdata

  // ==========================================================
  // perip defaults — load: narrow (raw addr/size); store: 8B aligned
  // ==========================================================
  perip.req := false.B
  perip.wen := ctrl.w_en
  perip.size := Mux(ctrl.w_en, dataBytesBits.U, ctrl.size)
  perip.addr := Mux(ctrl.w_en, aligned_addr, ctrl.addr)(busAddrBits - 1, 0)
  perip.wstrb := Mux(ctrl.w_en, store_wstrb, 0.U)
  perip.wdata := Mux(ctrl.w_en, store_wdata, 0.U)

  // ==========================================================
  // LateExecIO defaults
  // ==========================================================
  late.done := false.B
  late.bits.result := load_final
  late.bits.rd_wen := ctrl.r_en

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
        when(ctrl.r_en || ctrl.w_en) {
          when(ctrl.is_mmio) { perip.req := true.B }
            .otherwise { dcache.req := true.B }
        }
        stateQ := LSUState.lsu_req
      }
    }
    is(LSUState.lsu_req) {
      when(ctrl.is_mmio) {
        perip.req := true.B
        when(perip.ack) { stateQ := LSUState.lsu_wait }
      }.otherwise {
        dcache.req := true.B
        when(dcache.ack) { stateQ := LSUState.lsu_wait }
      }
    }
    is(LSUState.lsu_wait) {
      val data_ok = Mux(ctrl.is_mmio, perip.done, dcache.done)
      when(data_ok) {
        late.done := true.B
        when(ctrl.w_en) { late.bits.rd_wen := false.B }
        stateQ := LSUState.idle
      }
    }
  }
}
