package ysyx.core.component

import chisel3._
import chisel3.util._
import ysyx.core.common.HasCoreParameter
import freechips.rocketchip.amba.axi4._
import ysyx.CPUAXI4BundleParameters

class IFUOutputBundle extends Bundle with HasCoreParameter {
  val inst = UInt(InstLen.W)
  val pc   = UInt(XLEN.W)
  val isValid = Bool()
  val exception = IFUExceptionType()
  val xtval = UInt(XLEN.W)
  val exceptionEn = Bool()
}

class IFInputBundle extends Bundle with HasCoreParameter {
  val dnpc = UInt(XLEN.W)
}

object IFUExceptionType extends ChiselEnum {
  val ifu_INSTRUCTION_ADDRESS_MISALIGNED, ifu_INSTRUCTION_ACCESS_FAULT,
    ifu_INSTRUCTION_PAGE_FAULT
    = Value
}

object AXI4Resp {
  val OKAY   = 0.U(2.W)
  val EXOKAY = 1.U(2.W)
  val SLVERR = 2.U(2.W)
  val DECERR = 3.U(2.W)
}

class IFU extends Module with HasCoreParameter {
  val io = IO(new Bundle {
    val out = DecoupledIO(new IFUOutputBundle)
    val in  = Flipped(DecoupledIO(new IFInputBundle))
    val step = Input(Bool())
    val icache = AXI4Bundle(CPUAXI4BundleParameters())
  })

  io.icache.b.ready := true.B
  io.icache.aw.bits := DontCare
  io.icache.aw.bits.len := 0.U
  io.icache.aw.bits.size := 2.U
  io.icache.aw.bits.burst := 1.U
  io.icache.aw.valid := false.B
  io.icache.w.bits := DontCare
  io.icache.w.bits.last := true.B
  io.icache.w.valid := false.B

  private val pc_reg = RegInit("h80000000".U(XLEN.W))
  private val inst_reg = RegInit(0.U(InstLen.W))
  private val exception_reg = Reg(IFUExceptionType())
  private val exceptionEn_reg = RegInit(false.B)

  object State extends ChiselEnum {
    val idle, ar_wait, r_wait, allowin_wait, done_wait = Value
  }
  private val state = RegInit(State.idle)

  private val pcMisaligned = pc_reg(1, 0) =/= 0.U

  io.icache.ar.valid := (state === State.ar_wait) && !pcMisaligned
  io.icache.ar.bits.id := 0.U
  io.icache.ar.bits.addr := pc_reg
  io.icache.ar.bits.len := 0.U
  io.icache.ar.bits.size := 2.U
  io.icache.ar.bits.burst := 1.U
  io.icache.ar.bits.lock := 0.U
  io.icache.ar.bits.cache := 0.U
  io.icache.ar.bits.prot := Cat(true.B, false.B, true.B)
  io.icache.ar.bits.qos := 0.U

  io.icache.r.ready := (state === State.r_wait)

  io.out.valid := (state === State.allowin_wait)
  io.out.bits.inst := inst_reg
  io.out.bits.pc := pc_reg
  io.out.bits.isValid := (state === State.allowin_wait)
  io.out.bits.exception := exception_reg
  io.out.bits.xtval := pc_reg
  io.out.bits.exceptionEn := exceptionEn_reg
  io.in.ready := (state === State.done_wait)

  switch(state) {
    is(State.idle) {
      when(io.step) {
        when(pcMisaligned) {
          state := State.done_wait
          exception_reg := IFUExceptionType.ifu_INSTRUCTION_ADDRESS_MISALIGNED
          exceptionEn_reg := true.B
        } .otherwise {
          state := State.ar_wait
          exceptionEn_reg := false.B
        }
      }
    }
    is(State.ar_wait) {
      when(io.icache.ar.fire) {
        state := State.r_wait
      }
    }
    is(State.r_wait) {
      when(io.icache.r.fire) {
        state := State.allowin_wait
        inst_reg := io.icache.r.bits.data
        when(io.icache.r.bits.resp =/= AXI4Resp.OKAY) {
          exception_reg := IFUExceptionType.ifu_INSTRUCTION_ACCESS_FAULT
          exceptionEn_reg := true.B
        }
      }
    }
    is(State.allowin_wait) {
      when(io.out.fire) {
        state := State.done_wait
      }
    }
    is(State.done_wait) {
      when(io.in.fire) {
        state := State.idle
        pc_reg := io.in.bits.dnpc
      }
    }
  }
}
