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
  private val (ar, r, aw, w, b) = {
    val in = io.icache
    (in.ar, in.r, in.aw, in.w, in.b)
  }

  b.ready := true.B
  aw.bits := DontCare
  aw.bits.len := 0.U
  aw.bits.size := 2.U
  aw.bits.burst := 1.U
  aw.valid := false.B
  w.bits := DontCare
  w.bits.last := true.B
  w.valid := false.B

  private val pc_reg = RegInit(ysyx.SoCConfig.resetVector.U(XLEN.W))
  private val inst_reg = RegInit(0.U(InstLen.W))
  private val exception_reg = Reg(IFUExceptionType())
  private val exceptionEn_reg = RegInit(false.B)

  object State extends ChiselEnum {
    val idle, ar_wait, r_wait, allowin_wait, done_wait = Value
  }
  private val state = RegInit(State.idle)

  private val pcMisaligned = pc_reg(1, 0) =/= 0.U

  ar.valid := (state === State.ar_wait) && !pcMisaligned
  ar.bits.id := 0.U
  ar.bits.addr := pc_reg
  ar.bits.len := 0.U
  ar.bits.size := 2.U
  ar.bits.burst := 1.U
  ar.bits.lock := 0.U
  ar.bits.cache := 0.U
  ar.bits.prot := Cat(true.B, false.B, true.B)
  ar.bits.qos := 0.U

  r.ready := (state === State.r_wait)

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
      when(ar.fire) {
        state := State.r_wait
      }
    }
    is(State.r_wait) {
      when(r.fire) {
        state := State.allowin_wait
        inst_reg := r.bits.data
        when(r.bits.resp =/= AXI4Resp.OKAY) {
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
