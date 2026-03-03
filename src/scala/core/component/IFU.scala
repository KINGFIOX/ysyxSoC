package ysyx.core.component

import chisel3._
import chisel3.util._
import ysyx.core.common.HasCoreParameter
import freechips.rocketchip.amba.axi4._
import ysyx.CPUAXI4BundleParameters

class IFUOutputBundle extends Bundle with HasCoreParameter {
  val inst = UInt(InstLen.W)
  val pc = UInt(XLEN.W)
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
      ifu_INSTRUCTION_PAGE_FAULT = Value
}

object AXI4Resp {
  val OKAY = 0.U(2.W)
  val EXOKAY = 1.U(2.W)
  val SLVERR = 2.U(2.W)
  val DECERR = 3.U(2.W)
}

class IFU extends Module with HasCoreParameter {
  val io = IO(new Bundle {
    val out = DecoupledIO(new IFUOutputBundle)
    val in = Flipped(DecoupledIO(new IFInputBundle))
    val icache = AXI4Bundle(CPUAXI4BundleParameters())
  })
  private val (ar, r, aw, w, b) = {
    val in = io.icache
    (in.ar, in.r, in.aw, in.w, in.b)
  }

  // --- write related ---
  b.ready := false.B // impossible
  aw.valid := false.B; aw.bits := DontCare
  w.valid := false.B; w.bits := DontCare

  // --- register ---
  private val pcQ = RegInit(ysyx.SoCConfig.resetVector.U(XLEN.W))
  private val instQ = RegInit(0.U(InstLen.W))
  private val exceptQ = Reg(IFUExceptionType())
  private val exceptEnQ = RegInit(false.B)

  // --- state ---
  object State extends ChiselEnum {
    val ar_wait, r_wait, allowin_wait, done_wait = Value
  }
  private val stateQ = RegInit(State.ar_wait)

  // --- axi-ar-r ---
  ar.valid := (stateQ === State.ar_wait)
  ar.bits.id := 0.U
  ar.bits.addr := pcQ
  ar.bits.len := 0.U
  ar.bits.size := 2.U
  ar.bits.burst := 1.U
  ar.bits.lock := 0.U
  ar.bits.cache := 0.U
  ar.bits.prot := Cat(true.B, false.B, true.B)
  ar.bits.qos := 0.U
  r.ready := (stateQ === State.r_wait)

  // --- in core: downstream ---
  io.out.valid := (stateQ === State.allowin_wait)
  io.out.bits.inst := instQ
  io.out.bits.pc := pcQ
  io.out.bits.isValid := (stateQ === State.allowin_wait)
  io.out.bits.exception := exceptQ
  io.out.bits.xtval := pcQ
  io.out.bits.exceptionEn := exceptEnQ
  io.in.ready := (stateQ === State.done_wait)

  // --- state machine ---
  switch(stateQ) {

    is(State.ar_wait) {
      exceptEnQ := false.B // reset

      when(ar.fire) {
        stateQ := State.r_wait
      }
    }

    is(State.r_wait) {
      when(r.fire) {
        stateQ := State.allowin_wait
        instQ := r.bits.data
        when(r.bits.resp =/= AXI4Resp.OKAY) {
          exceptQ := IFUExceptionType.ifu_INSTRUCTION_ACCESS_FAULT
          exceptEnQ := true.B
        }
      }
    }

    is(State.allowin_wait) {
      when(io.out.fire) {
        stateQ := State.done_wait
      }
    }

    is(State.done_wait) {
      when(io.in.fire) {
        stateQ := State.ar_wait
        pcQ := io.in.bits.dnpc
      }
    }

  }
}
