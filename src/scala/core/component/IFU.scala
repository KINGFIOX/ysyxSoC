package ysyx.core.component

import chisel3._
import chisel3.util._
import ysyx.core.common._
import freechips.rocketchip.amba.axi4._
import scala.annotation.meta.param

class IFUOutputBundle extends Bundle with HasCoreParameter {
  val inst = UInt(InstBits.W)
  val pc = UInt(dataBits.W)
  val isValid = Bool()
  val exception = IFUExceptionType()
  val xtval = UInt(dataBits.W)
  val exceptionEn = Bool()
}

class IFInputBundle extends Bundle with HasCoreParameter {
  val dnpc = UInt(dataBits.W)
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

class IFU(axiParams: AXI4BundleParameters) extends NPCModule {

  // io
  val io = IO(new Bundle {
    val out = Irrevocable(new IFUOutputBundle)
    val in = Flipped(Irrevocable(new IFInputBundle))
  })

  val icache = IO(AXI4Bundle(axiParams))

  private val loadUnit = Module(new LoadUnit(axiParams, 0))

  // connect LoadUnit AXI ports to icache
  loadUnit.ar <> icache.ar
  loadUnit.r <> icache.r

  // aw, w, b not used for instruction fetch
  icache.b.ready := false.B
  icache.aw.valid := false.B
  icache.aw.bits := DontCare
  icache.w.valid := false.B
  icache.w.bits := DontCare

  // --- registers ---
  private val pcQ = RegInit(ysyx.SoCConfig.resetVector.U(addrBits.W))
  private val instQ = RegInit(0.U(dataBits.W))
  private val exceptQ = Reg(IFUExceptionType())
  private val exceptEnQ = RegInit(false.B)

  // --- state ---
  object State extends ChiselEnum {
    val idle, addr_req, data_wait, allowin_wait, done_wait = Value
  }
  private val stateQ = RegInit(State.idle)

  // --- LoadUnit SRAM interface ---
  loadUnit.in.req := (stateQ === State.addr_req)
  loadUnit.in.wr := false.B
  loadUnit.in.size := 2.U
  loadUnit.in.addr := pcQ
  loadUnit.in.wstrb := 0.U
  loadUnit.in.wdata := 0.U

  // --- downstream ---
  io.out.valid := (stateQ === State.allowin_wait)
  io.out.bits.inst := instQ
  io.out.bits.pc := pcQ
  io.out.bits.isValid := (stateQ === State.allowin_wait)
  io.out.bits.exception := exceptQ
  io.out.bits.xtval := pcQ
  io.out.bits.exceptionEn := exceptEnQ

  // --- upstream ---
  io.in.ready := (stateQ === State.done_wait)

  // --- state machine ---
  switch(stateQ) {

    is(State.idle) {
      stateQ := State.addr_req
    }

    is(State.addr_req) {
      exceptEnQ := false.B // reset
      when(loadUnit.in.addr_ok) {
        stateQ := State.data_wait
      }
    }

    is(State.data_wait) {
      when(loadUnit.in.data_ok) {
        stateQ := State.allowin_wait
        instQ := loadUnit.in.rdata
        when(loadUnit.in.resp =/= AXI4Resp.OKAY) {
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
        stateQ := State.addr_req
        pcQ := io.in.bits.dnpc
      }
    }

  }
}
