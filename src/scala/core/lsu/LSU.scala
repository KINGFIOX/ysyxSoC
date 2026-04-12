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
  val satp_in = IO(Input(UInt(64.W)))
  val priv_in = IO(Input(UInt(2.W)))
  val sfence_vma = IO(Input(Bool()))

  prf(0).addr := late.bits.prs1
  prf(1).addr := late.bits.prs2
  val vaddr = prf(0).data + late.bits.imm
  val wdata = prf(1).data

  val ctrl = late.bits

  // MMU: dTLB + inline PTW
  val dtlb = Module(new TLB(numEntries = 16))
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
  dtlb.io.refill.level := 0.U
  dtlb.io.flush := sfence_vma

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

  // PTW state registers (inline PTW using dcache)
  val ptw_vpn = RegInit(0.U(27.W))
  val ptw_level = RegInit(0.U(2.W))
  val ptw_pte = RegInit(0.U(64.W))
  val satp_ppn = satp_in(43, 0)

  def pte_v(pte: UInt) = pte(0)
  def pte_r(pte: UInt) = pte(1)
  def pte_w(pte: UInt) = pte(2)
  def pte_x(pte: UInt) = pte(3)
  def pte_ppn(pte: UInt) = pte(53, 10)
  def is_leaf(pte: UInt) = pte_r(pte) || pte_x(pte)

  val ptw_vpn2 = ptw_vpn(26, 18)
  val ptw_vpn1 = ptw_vpn(17, 9)
  val ptw_vpn0 = ptw_vpn(8, 0)
  val ptw_l2_addr = Cat(satp_ppn, 0.U(12.W)) + Cat(ptw_vpn2, 0.U(3.W))
  val ptw_l1_addr = Cat(pte_ppn(ptw_pte), 0.U(12.W)) + Cat(ptw_vpn1, 0.U(3.W))
  val ptw_l0_addr = Cat(pte_ppn(ptw_pte), 0.U(12.W)) + Cat(ptw_vpn0, 0.U(3.W))

  object LSUState extends ChiselEnum {
    val idle, tlb_check, ptw_l2_req, ptw_l2_wait, ptw_l1_req, ptw_l1_wait,
        ptw_l0_req, ptw_l0_wait, lsu_req, lsu_wait = Value
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
            ptw_vpn := vpn
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
        val pte_u = tlb_flags(4)
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
        // TLB miss: start inline PTW
        stateQ := LSUState.ptw_l2_req
      }
    }

    // PTW Level 2
    is(LSUState.ptw_l2_req) {
      dcache.req := true.B
      dcache.wen := false.B
      dcache.size := 3.U
      dcache.addr := ptw_l2_addr(busAddrBits - 1, 0)
      dcache.wstrb := 0.U
      dcache.wdata := 0.U
      when(dcache.ack) {
        stateQ := LSUState.ptw_l2_wait
      }
    }
    is(LSUState.ptw_l2_wait) {
      when(dcache.done) {
        ptw_pte := dcache.rdata
        val pte = dcache.rdata
        when(!pte_v(pte)) {
          late.done := true.B
          late.bits.rd_wen := false.B
          late.bits.page_fault := true.B
          late.bits.page_fault_cause := Mux(ctrl.r_en, 13.U, 15.U)
          late.bits.page_fault_addr := vaddr
          stateQ := LSUState.idle
        }.elsewhen(is_leaf(pte)) {
          ptw_level := 2.U
          dtlb.io.refill.valid := true.B
          dtlb.io.refill.ppn := pte_ppn(pte)
          dtlb.io.refill.flags := pte(7, 0)
          dtlb.io.refill.level := 2.U
          stateQ := LSUState.tlb_check
        }.otherwise {
          stateQ := LSUState.ptw_l1_req
        }
      }
    }

    // PTW Level 1
    is(LSUState.ptw_l1_req) {
      dcache.req := true.B
      dcache.wen := false.B
      dcache.size := 3.U
      dcache.addr := ptw_l1_addr(busAddrBits - 1, 0)
      dcache.wstrb := 0.U
      dcache.wdata := 0.U
      when(dcache.ack) {
        stateQ := LSUState.ptw_l1_wait
      }
    }
    is(LSUState.ptw_l1_wait) {
      when(dcache.done) {
        ptw_pte := dcache.rdata
        val pte = dcache.rdata
        when(!pte_v(pte)) {
          late.done := true.B
          late.bits.rd_wen := false.B
          late.bits.page_fault := true.B
          late.bits.page_fault_cause := Mux(ctrl.r_en, 13.U, 15.U)
          late.bits.page_fault_addr := vaddr
          stateQ := LSUState.idle
        }.elsewhen(is_leaf(pte)) {
          ptw_level := 1.U
          dtlb.io.refill.valid := true.B
          dtlb.io.refill.ppn := pte_ppn(pte)
          dtlb.io.refill.flags := pte(7, 0)
          dtlb.io.refill.level := 1.U
          stateQ := LSUState.tlb_check
        }.otherwise {
          stateQ := LSUState.ptw_l0_req
        }
      }
    }

    // PTW Level 0
    is(LSUState.ptw_l0_req) {
      dcache.req := true.B
      dcache.wen := false.B
      dcache.size := 3.U
      dcache.addr := ptw_l0_addr(busAddrBits - 1, 0)
      dcache.wstrb := 0.U
      dcache.wdata := 0.U
      when(dcache.ack) {
        stateQ := LSUState.ptw_l0_wait
      }
    }
    is(LSUState.ptw_l0_wait) {
      when(dcache.done) {
        val pte = dcache.rdata
        when(!pte_v(pte) || (!pte_r(pte) && pte_w(pte))) {
          late.done := true.B
          late.bits.rd_wen := false.B
          late.bits.page_fault := true.B
          late.bits.page_fault_cause := Mux(ctrl.r_en, 13.U, 15.U)
          late.bits.page_fault_addr := vaddr
          stateQ := LSUState.idle
        }.otherwise {
          dtlb.io.refill.valid := true.B
          dtlb.io.refill.ppn := pte_ppn(pte)
          dtlb.io.refill.flags := pte(7, 0)
          dtlb.io.refill.level := 0.U
          stateQ := LSUState.tlb_check
        }
      }
    }

    // Normal memory access (after translation)
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
        is_translated := false.B
      }
    }
  }
}
