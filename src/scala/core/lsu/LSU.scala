package ysyx.core.lsu

import chisel3._
import chisel3.util._

import freechips.rocketchip.amba.axi4._
import freechips.rocketchip.util._

import ysyx.core.backend._
import ysyx.core.common._
import ysyx.core.sram._
import ysyx.core.mmu.{TLB, PTW}
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

class MemLate extends MemInfoBundle {
  val prs1 = UInt(NRPhyRegBits.W)
  val prs2 = UInt(NRPhyRegBits.W)
  val imm = UInt(dataBits.W)
  val is_mmio = Input(Bool())
  val result = Input(UInt(dataBits.W))
  val rd_wen = Input(Bool())
  val page_fault = Input(Bool())
  val page_fault_cause = Input(UInt(dataBits.W))
  val page_fault_addr = Input(UInt(dataBits.W))
}

class LSU extends LateExecUnit(new MemLate, 2) {
  val dcache = IO(SRAMBundle(sramParams))
  val perip = IO(SRAMBundle(sramParams))
  val ptw_port = IO(SRAMBundle(sramParams))
  val satp_in = IO(Input(UInt(64.W)))
  val priv_in = IO(Input(UInt(2.W)))
  val sfence_vma = IO(Input(Bool()))

  prf(0).addr := late.bits.prs1
  prf(1).addr := late.bits.prs2
  val vaddr = prf(0).data + late.bits.imm
  val wdata = prf(1).data

  val ctrl = late.bits

  // MMU: dTLB + PTW
  val dtlb = Module(new TLB(numEntries = 16))
  val ptw = Module(new PTW)

  val sv39_enabled = satp_in(63, 60) === 8.U
  val vpn = vaddr(38, 12)
  val page_offset = vaddr(11, 0)

  dtlb.io.lookup.req.vpn := vpn
  val tlb_hit = dtlb.io.lookup.resp.hit && sv39_enabled
  val tlb_ppn = dtlb.io.lookup.resp.ppn
  val tlb_flags = dtlb.io.lookup.resp.flags
  val tlb_pa = Cat(tlb_ppn, page_offset)

  dtlb.io.refill.valid := false.B
  dtlb.io.refill.vpn := vpn
  dtlb.io.refill.ppn := 0.U
  dtlb.io.refill.flags := 0.U
  dtlb.io.flush := sfence_vma

  // PTW connections
  ptw.io.satp := satp_in
  ptw.io.priv := priv_in
  ptw_port <> ptw.io.mem
  ptw.io.req.valid := false.B
  ptw.io.req.bits.vpn := vpn

  // Translated or bare physical address
  val pa = RegInit(0.U(addrBits.W))
  val is_translated = RegInit(false.B)

  val addr = Mux(is_translated, pa, vaddr)

  late.bits.is_mmio := AddressMap.is_mmio(addr)
  val aligned_addr = Cat(addr(addrBits - 1, dataBytesBits), 0.U(dataBytesBits.W))

  // Store wstrb / wdata formatting
  val store_wstrb = MuxLookup(ctrl.size, "b11111111".U)(
    Seq(
      0.U -> (1.U(dataBytes.W) << addr(dataBytesBits - 1, 0)),
      1.U -> (3.U(dataBytes.W) << Cat(addr(dataBytesBits - 1, 1), 0.U(1.W))),
      2.U -> ("b1111".U(dataBytes.W) << Cat(addr(dataBytesBits - 1, 2), 0.U(2.W))),
      3.U -> "b11111111".U(dataBytes.W)
    )
  )
  val store_wdata = MuxLookup(ctrl.size, wdata)(
    Seq(
      0.U -> Fill(dataBytes, wdata(7, 0)),
      1.U -> Fill(dataBytes / 2, wdata(15, 0)),
      2.U -> Fill(dataBytes / 4, wdata(31, 0)),
      3.U -> wdata
    )
  )

  // Load data extraction
  val cache_raw = dcache.rdata
  val byte_off = addr(dataBytesBits - 1, 0)
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

  // dcache defaults
  dcache.req := false.B
  dcache.wen := ctrl.w_en
  dcache.size := dataBytesBits.U
  dcache.addr := aligned_addr(busAddrBits - 1, 0)
  dcache.wstrb := store_wstrb
  dcache.wdata := store_wdata

  // perip defaults
  perip.req := false.B
  perip.wen := ctrl.w_en
  perip.size := Mux(ctrl.w_en, dataBytesBits.U, ctrl.size)
  perip.addr := Mux(ctrl.w_en, aligned_addr, addr)(busAddrBits - 1, 0)
  perip.wstrb := Mux(ctrl.w_en, store_wstrb, 0.U)
  perip.wdata := Mux(ctrl.w_en, store_wdata, 0.U)

  // LateExecIO defaults
  late.done := false.B
  late.bits.result := load_final
  late.bits.rd_wen := ctrl.r_en
  late.bits.page_fault := false.B
  late.bits.page_fault_cause := 0.U
  late.bits.page_fault_addr := 0.U

  object LSUState extends ChiselEnum {
    val idle, tlb_check, ptw_req, ptw_wait, lsu_req, lsu_wait = Value
  }
  val stateQ = RegInit(LSUState.idle)

  switch(stateQ) {
    is(LSUState.idle) {
      when(late.req) {
        when(ctrl.r_en || ctrl.w_en) {
          when(!sv39_enabled) {
            is_translated := false.B
            stateQ := LSUState.lsu_req
          }.otherwise {
            stateQ := LSUState.tlb_check
          }
        }.otherwise {
          late.done := true.B
          late.bits.rd_wen := false.B
        }
      }
    }

    is(LSUState.tlb_check) {
      when(tlb_hit) {
        val pte_r_ok = ctrl.r_en && tlb_flags(1)
        val pte_w_ok = ctrl.w_en && tlb_flags(2)
        val access_ok = Mux(ctrl.r_en, pte_r_ok, pte_w_ok)
        when(!access_ok) {
          late.done := true.B
          late.bits.rd_wen := false.B
          late.bits.page_fault := true.B
          late.bits.page_fault_cause := Mux(ctrl.r_en, 13.U, 15.U)
          late.bits.page_fault_addr := vaddr
          stateQ := LSUState.idle
        }.otherwise {
          pa := tlb_pa
          is_translated := true.B
          stateQ := LSUState.lsu_req
        }
      }.otherwise {
        stateQ := LSUState.ptw_req
      }
    }

    is(LSUState.ptw_req) {
      ptw.io.req.valid := true.B
      when(ptw.io.req.fire) {
        stateQ := LSUState.ptw_wait
      }
    }

    is(LSUState.ptw_wait) {
      when(ptw.io.resp.valid) {
        when(ptw.io.resp.bits.fault) {
          late.done := true.B
          late.bits.rd_wen := false.B
          late.bits.page_fault := true.B
          late.bits.page_fault_cause := Mux(ctrl.r_en, 13.U, 15.U)
          late.bits.page_fault_addr := vaddr
          stateQ := LSUState.idle
        }.otherwise {
          dtlb.io.refill.valid := true.B
          dtlb.io.refill.ppn := ptw.io.resp.bits.ppn
          dtlb.io.refill.flags := ptw.io.resp.bits.flags
          stateQ := LSUState.tlb_check
        }
      }
    }

    // Normal memory access (after translation)
    is(LSUState.lsu_req) {
      when(ctrl.is_mmio) {
        perip.req := true.B
        when(perip.ack) {
          when(perip.done) {
            late.done := true.B
            when(ctrl.w_en) { late.bits.rd_wen := false.B }
            stateQ := LSUState.idle
            is_translated := false.B
          }.otherwise {
            stateQ := LSUState.lsu_wait
          }
        }
      }.otherwise {
        dcache.req := true.B
        when(dcache.ack) {
          when(dcache.done) {
            late.done := true.B
            when(ctrl.w_en) { late.bits.rd_wen := false.B }
            stateQ := LSUState.idle
            is_translated := false.B
          }.otherwise {
            stateQ := LSUState.lsu_wait
          }
        }
      }
    }
    is(LSUState.lsu_wait) {
      val data_ok = Mux(ctrl.is_mmio, perip.done, dcache.done)
      when(data_ok) {
        late.done := true.B
        when(ctrl.w_en) { late.bits.rd_wen := false.B }
        stateQ := LSUState.idle
        is_translated := false.B
      }
    }
  }
}
