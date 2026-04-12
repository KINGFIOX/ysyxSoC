package ysyx.core.frontend

import chisel3._
import chisel3.util._

import freechips.rocketchip.amba.axi4._
import freechips.rocketchip.util._

import ysyx.core.common._
import ysyx.core.lsu._
import ysyx.core.sram._
import ysyx.core.backend.InstType
import ysyx.core.backend.{BruRobEntry, JalRobEntry, JalrRobEntry, MretRobEntry, ExceptRobEntry}
import ysyx.core.mmu.{TLB, PTW}

class IFUOutput extends NPCBundle {
  val inst = UInt(instBits.W)
  val pc = UInt(addrBits.W)
  val predict_npc = UInt(addrBits.W)
  val ghr = UInt(ghrBits.W)
  val mcause = UInt(dataBits.W)
  val mtval = UInt(dataBits.W)
  val has_except = Bool()
}

class RedirectBundle extends NPCBundle {
  val wrong_pc = UInt(addrBits.W)
  val inst_type = InstType()
  val mispredict = Bool()
  val bru = new BruRobEntry
  val jal = new JalRobEntry
  val jalr = new JalrRobEntry
  val ghr = UInt(ghrBits.W)
  val dnpc = UInt(addrBits.W)
}

class PredictBundle extends NPCBundle {
  val dnpc = UInt(addrBits.W)
  val ghr = UInt(ghrBits.W)
  val pc = Input(UInt(addrBits.W))
  val inst = Input(UInt(instBits.W))
}

class IFU extends NPCModule {

  val io = IO(new Bundle {
    val out = Irrevocable(new IFUOutput)
    val predict = Flipped(new PredictBundle)
    val flush = Input(Bool())
    val dnpc = Input(UInt(dataBits.W))
    val satp = Input(UInt(dataBits.W))
    val priv = Input(UInt(2.W))
    val sfence_vma = Input(Bool())
  })

  val icache = IO(SRAMBundle(sramParams))
  val ptw_port = IO(SRAMBundle(sramParams))

  val pc_q = RegInit(ysyx.SoCConfig.resetVector.U(addrBits.W))
  val inst_q = icache.rdata holdUnless icache.done

  // MMU: iTLB + PTW
  val itlb = Module(new TLB(numEntries = 16))
  val ptw = Module(new PTW)

  val sv39_enabled = io.satp(63, 60) === 8.U
  val va = pc_q
  val vpn = va(38, 12)
  val page_offset = va(11, 0)

  // iTLB lookup (always driven)
  itlb.io.lookup.req.vpn := vpn
  val tlb_hit = itlb.io.lookup.resp.hit && sv39_enabled
  val tlb_ppn = itlb.io.lookup.resp.ppn
  val tlb_flags = itlb.io.lookup.resp.flags
  val tlb_pa = Cat(tlb_ppn, page_offset)

  // TLB refill defaults
  itlb.io.refill.valid := false.B
  itlb.io.refill.vpn := vpn
  itlb.io.refill.ppn := 0.U
  itlb.io.refill.flags := 0.U
  itlb.io.refill.level := 0.U
  itlb.io.flush := io.sfence_vma

  // PTW connections
  ptw.io.satp := io.satp
  ptw.io.priv := io.priv
  ptw_port <> ptw.io.mem
  ptw.io.req.valid := false.B
  ptw.io.req.bits.vpn := vpn

  // Physical address (translated or bare)
  val pa_reg = RegInit(0.U(busAddrBits.W))
  val page_fault_reg = RegInit(false.B)

  // Select the correct 32-bit word from the 64-bit cache line beat
  val inst_word = Mux(pa_reg(2), inst_q(63, 32), inst_q(31, 0))

  object State extends ChiselEnum {
    val idle, tlb_check, ptw_req, ptw_wait, addr_req, data_wait, output_wait = Value
  }
  val state_q = RegInit(State.idle)

  // icache defaults
  icache.req := false.B
  icache.wen := false.B
  icache.size := 2.U
  icache.addr := pa_reg
  icache.wstrb := 0.U
  icache.wdata := 0.U

  io.out.valid := (state_q === State.output_wait)
  io.out.bits.inst := inst_word
  io.out.bits.pc := pc_q
  io.out.bits.mcause := Mux(page_fault_reg, 12.U, 0.U) // instruction page fault
  io.out.bits.mtval := Mux(page_fault_reg, pc_q, 0.U)
  io.out.bits.has_except := page_fault_reg
  io.out.bits.predict_npc := io.predict.dnpc
  io.out.bits.ghr := io.predict.ghr
  io.predict.pc := pc_q
  io.predict.inst := inst_word

  switch(state_q) {

    is(State.idle) {
      state_q := State.tlb_check
    }

    is(State.tlb_check) {
      page_fault_reg := false.B
      when(!sv39_enabled) {
        pa_reg := pc_q(busAddrBits - 1, 0)
        state_q := State.addr_req
      }.elsewhen(tlb_hit) {
        val pte_x = tlb_flags(3)
        when(!pte_x) {
          page_fault_reg := true.B
          state_q := State.output_wait
        }.otherwise {
          pa_reg := tlb_pa(busAddrBits - 1, 0)
          state_q := State.addr_req
        }
      }.otherwise {
        // TLB miss: start PTW
        state_q := State.ptw_req
      }
    }

    is(State.ptw_req) {
      ptw.io.req.valid := true.B
      when(ptw.io.req.fire) {
        state_q := State.ptw_wait
      }
    }

    is(State.ptw_wait) {
      when(ptw.io.resp.valid) {
        when(ptw.io.resp.bits.fault) {
          page_fault_reg := true.B
          state_q := State.output_wait
        }.otherwise {
          itlb.io.refill.valid := true.B
          itlb.io.refill.ppn := ptw.io.resp.bits.ppn
          itlb.io.refill.flags := ptw.io.resp.bits.flags
          itlb.io.refill.level := ptw.io.resp.bits.level
          state_q := State.tlb_check // re-check TLB after refill
        }
      }
    }

    is(State.addr_req) {
      icache.req := true.B
      icache.addr := pa_reg
      when(icache.ack) {
        state_q := State.data_wait
      }
    }

    is(State.data_wait) {
      when(icache.done) {
        state_q := State.output_wait
      }
    }

    is(State.output_wait) {
      when(io.out.fire) {
        state_q := State.tlb_check
        pc_q := io.predict.dnpc
      }
    }
  }

  when(io.flush) {
    state_q := State.tlb_check
    pc_q := io.dnpc
    page_fault_reg := false.B
  }
}
