package ysyx.core.mmu

import chisel3._
import chisel3.util._

import ysyx.core.common._
import ysyx.core.sram._

class PTWReq extends Bundle {
  val vpn = UInt(27.W)
}

class PTWResp extends Bundle {
  val ppn      = UInt(44.W)
  val flags    = UInt(8.W)
  val fault    = Bool()
}

class PTW extends NPCModule {
  val io = IO(new Bundle {
    val req   = Flipped(Decoupled(new PTWReq))
    val resp  = Valid(new PTWResp)
    val satp  = Input(UInt(dataBits.W))
    val priv  = Input(UInt(2.W))
    val mem   = SRAMBundle(sramParams)
  })

  object State extends ChiselEnum {
    val idle, level2_req, level2_wait, level1_req, level1_wait,
        level0_req, level0_wait, done, fault = Value
  }
  val state = RegInit(State.idle)

  val vpn_reg = RegInit(0.U(27.W))
  val pte_reg = RegInit(0.U(dataBits.W))

  val satp_ppn = io.satp(43, 0)

  def pte_v(pte: UInt) = pte(0)
  def pte_r(pte: UInt) = pte(1)
  def pte_w(pte: UInt) = pte(2)
  def pte_ppn(pte: UInt) = pte(53, 10)

  val vpn2 = vpn_reg(26, 18)
  val vpn1 = vpn_reg(17, 9)
  val vpn0 = vpn_reg(8, 0)

  val level2_addr = Cat(satp_ppn, 0.U(12.W)) + Cat(vpn2, 0.U(3.W))
  val level1_addr = Cat(pte_ppn(pte_reg), 0.U(12.W)) + Cat(vpn1, 0.U(3.W))
  val level0_addr = Cat(pte_ppn(pte_reg), 0.U(12.W)) + Cat(vpn0, 0.U(3.W))

  io.mem.req := false.B
  io.mem.wen := false.B
  io.mem.size := 3.U
  io.mem.addr := 0.U
  io.mem.wstrb := 0.U
  io.mem.wdata := 0.U

  io.req.ready := state === State.idle
  io.resp.valid := (state === State.done) || (state === State.fault)
  io.resp.bits.ppn := pte_ppn(pte_reg)
  io.resp.bits.flags := pte_reg(7, 0)
  io.resp.bits.fault := state === State.fault

  switch(state) {
    is(State.idle) {
      when(io.req.fire) {
        vpn_reg := io.req.bits.vpn
        state := State.level2_req
      }
    }

    is(State.level2_req) {
      io.mem.req := true.B
      io.mem.addr := level2_addr.pad(busAddrBits)
      when(io.mem.ack) {
        state := State.level2_wait
      }
    }
    is(State.level2_wait) {
      when(io.mem.done) {
        pte_reg := io.mem.rdata
        val pte = io.mem.rdata
        when(!pte_v(pte)) {
          state := State.fault
        }.otherwise {
          state := State.level1_req
        }
      }
    }

    is(State.level1_req) {
      io.mem.req := true.B
      io.mem.addr := level1_addr.pad(busAddrBits)
      when(io.mem.ack) {
        state := State.level1_wait
      }
    }
    is(State.level1_wait) {
      when(io.mem.done) {
        pte_reg := io.mem.rdata
        val pte = io.mem.rdata
        when(!pte_v(pte)) {
          state := State.fault
        }.otherwise {
          state := State.level0_req
        }
      }
    }

    is(State.level0_req) {
      io.mem.req := true.B
      io.mem.addr := level0_addr.pad(busAddrBits)
      when(io.mem.ack) {
        state := State.level0_wait
      }
    }
    is(State.level0_wait) {
      when(io.mem.done) {
        pte_reg := io.mem.rdata
        val pte = io.mem.rdata
        when(!pte_v(pte) || (!pte_r(pte) && pte_w(pte))) {
          state := State.fault
        }.otherwise {
          state := State.done
        }
      }
    }

    is(State.done) {
      state := State.idle
    }
    is(State.fault) {
      state := State.idle
    }
  }
}
