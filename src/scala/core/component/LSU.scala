package ysyx.core.component

import chisel3._
import chisel3.util._
import ysyx.core.common.HasCoreParameter
import freechips.rocketchip.amba.axi4._
import ysyx.CPUAXI4BundleParameters

object MemUOpType extends ChiselEnum {
  val mem_LB, mem_LH, mem_LW, mem_LBU, mem_LHU, mem_SB, mem_SH, mem_SW = Value
}

object MemUExceptionType extends ChiselEnum {
  val mem_LOAD_ADDRESS_MISALIGNED, mem_LOAD_ACCESS_FAULT,
    mem_STORE_ADDRESS_MISALIGNED, mem_STORE_ACCESS_FAULT,
    mem_LOAD_PAGE_FAULT, mem_STORE_PAGE_FAULT
    = Value
}

class MEMUInputBundle extends Bundle with HasCoreParameter {
  val op    = MemUOpType()
  val wdata = UInt(XLEN.W)
  val addr  = UInt(XLEN.W)
  val en = Bool()
}

class MEMUOutputBundle extends Bundle with HasCoreParameter {
  val rdata = UInt(XLEN.W)
  val exception = MemUExceptionType()
  val exceptionEn = Bool()
  val xtval = UInt(XLEN.W)
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

class LSU extends Module with HasCoreParameter {
  val io = IO(new Bundle {
    val in     = Flipped(DecoupledIO(new MEMUInputBundle))
    val out    = DecoupledIO(new MEMUOutputBundle)
    val dcache = AXI4Bundle(CPUAXI4BundleParameters())
  })

  private val isLoad  = io.in.bits.op.isOneOf(MemUOpType.mem_LB, MemUOpType.mem_LH, MemUOpType.mem_LW, MemUOpType.mem_LBU, MemUOpType.mem_LHU)
  private val isStore = io.in.bits.op.isOneOf(MemUOpType.mem_SB, MemUOpType.mem_SH, MemUOpType.mem_SW)

  private val addr = io.in.bits.addr
  private val addrAlign2 = addr(0) === 0.U
  private val addrAlign4 = addr(1, 0) === 0.U

  private val loadMisaligned = MuxLookup(io.in.bits.op.asUInt, false.B)(Seq(
    MemUOpType.mem_LH.asUInt  -> !addrAlign2,
    MemUOpType.mem_LHU.asUInt -> !addrAlign2,
    MemUOpType.mem_LW.asUInt  -> !addrAlign4
  ))

  private val storeMisaligned = MuxLookup(io.in.bits.op.asUInt, false.B)(Seq(
    MemUOpType.mem_SH.asUInt -> !addrAlign2,
    MemUOpType.mem_SW.asUInt -> !addrAlign4
  ))

  private val op_reg    = RegInit(MemUOpType.mem_LB)
  private val addr_reg  = RegInit(0.U(XLEN.W))
  private val wdata_reg = RegInit(0.U(XLEN.W))
  private val exception_reg = Reg(MemUExceptionType())
  private val exceptionEn_reg = RegInit(false.B)

  object ReadState extends ChiselEnum {
    val idle, ar_wait, r_wait, done = Value
  }
  private val read_state = RegInit(ReadState.idle)

  object WriteState extends ChiselEnum {
    val idle, aw_w_wait, b_wait, done = Value
  }
  private val write_state = RegInit(WriteState.idle)

  private val aw_sent = RegInit(false.B)
  private val w_sent  = RegInit(false.B)

  switch(read_state) {
    is(ReadState.idle) {
      when(io.in.fire && isLoad && io.in.bits.en) {
        op_reg   := io.in.bits.op
        addr_reg := io.in.bits.addr
        when(loadMisaligned) {
          exception_reg := MemUExceptionType.mem_LOAD_ADDRESS_MISALIGNED
          exceptionEn_reg := true.B
          read_state := ReadState.done
        }.otherwise {
          read_state := ReadState.ar_wait
        }
      }
    }
    is(ReadState.ar_wait) {
      when(io.dcache.ar.fire) {
        read_state := ReadState.r_wait
      }
    }
    is(ReadState.r_wait) {
      when(io.dcache.r.fire) {
        when(io.dcache.r.bits.resp =/= AXI4Resp.OKAY) {
          exception_reg := MemUExceptionType.mem_LOAD_ACCESS_FAULT
          exceptionEn_reg := true.B
        }
        read_state := ReadState.done
      }
    }
    is(ReadState.done) {
      when(io.out.fire) {
        read_state := ReadState.idle
      }
    }
  }

  switch(write_state) {
    is(WriteState.idle) {
      when(io.in.fire && isStore && io.in.bits.en) {
        op_reg    := io.in.bits.op
        addr_reg  := io.in.bits.addr
        wdata_reg := io.in.bits.wdata
        when(storeMisaligned) {
          exception_reg := MemUExceptionType.mem_STORE_ADDRESS_MISALIGNED
          exceptionEn_reg := true.B
          write_state := WriteState.done
        }.otherwise {
          write_state := WriteState.aw_w_wait
          aw_sent := false.B
          w_sent  := false.B
          exceptionEn_reg := false.B
        }
      }
    }
    is(WriteState.aw_w_wait) {
      when(io.dcache.aw.fire) {
        aw_sent := true.B
      }
      when(io.dcache.w.fire) {
        w_sent := true.B
      }
      val aw_done = aw_sent || io.dcache.aw.fire
      val w_done  = w_sent || io.dcache.w.fire
      when(aw_done && w_done) {
        write_state := WriteState.b_wait
      }
    }
    is(WriteState.b_wait) {
      when(io.dcache.b.fire) {
        when(io.dcache.b.bits.resp =/= AXI4Resp.OKAY) {
          exception_reg := MemUExceptionType.mem_STORE_ACCESS_FAULT
          exceptionEn_reg := true.B
        }
        write_state := WriteState.done
      }
    }
    is(WriteState.done) {
      when(io.out.fire) {
        write_state := WriteState.idle
      }
    }
  }

  // AR channel
  io.dcache.ar.valid     := (read_state === ReadState.ar_wait)
  io.dcache.ar.bits.id   := 0.U
  io.dcache.ar.bits.addr := Cat(addr_reg(XLEN - 1, 2), 0.U(2.W))
  io.dcache.ar.bits.len  := 0.U
  io.dcache.ar.bits.size := 2.U
  io.dcache.ar.bits.burst := 1.U
  io.dcache.ar.bits.lock := 0.U
  io.dcache.ar.bits.cache := 0.U
  io.dcache.ar.bits.prot := Cat(false.B, false.B, true.B)
  io.dcache.ar.bits.qos := 0.U

  io.dcache.r.ready := (read_state === ReadState.r_wait)

  private val rdata_raw = io.dcache.r.bits.data
  private val rdata_reg = RegInit(0.U(XLEN.W))

  private val byte_offset = addr_reg(1, 0)
  private val rdata_byte = (rdata_raw >> (byte_offset << 3.U))(7, 0)
  private val rdata_half = Mux(addr_reg(1), rdata_raw(31, 16), rdata_raw(15, 0))

  when(io.dcache.r.fire) {
    rdata_reg := MuxLookup(op_reg.asUInt, rdata_raw)(Seq(
      MemUOpType.mem_LB.asUInt  -> SignExt(rdata_byte, XLEN),
      MemUOpType.mem_LBU.asUInt -> ZeroExt(rdata_byte, XLEN),
      MemUOpType.mem_LH.asUInt  -> SignExt(rdata_half, XLEN),
      MemUOpType.mem_LHU.asUInt -> ZeroExt(rdata_half, XLEN),
      MemUOpType.mem_LW.asUInt  -> rdata_raw
    ))
  }

  // AW channel
  io.dcache.aw.valid     := (write_state === WriteState.aw_w_wait) && !aw_sent
  io.dcache.aw.bits.id   := 0.U
  io.dcache.aw.bits.addr := Cat(addr_reg(XLEN - 1, 2), 0.U(2.W))
  io.dcache.aw.bits.len  := 0.U
  io.dcache.aw.bits.size := 2.U
  io.dcache.aw.bits.burst := 1.U
  io.dcache.aw.bits.lock := 0.U
  io.dcache.aw.bits.cache := 0.U
  io.dcache.aw.bits.prot := 0.U
  io.dcache.aw.bits.qos := 0.U

  // W channel
  io.dcache.w.valid := (write_state === WriteState.aw_w_wait) && !w_sent

  private val wstrb = MuxLookup(op_reg.asUInt, "b1111".U(4.W))(Seq(
    MemUOpType.mem_SB.asUInt -> ("b0001".U << byte_offset),
    MemUOpType.mem_SH.asUInt -> Mux(addr_reg(1), "b1100".U(4.W), "b0011".U(4.W)),
    MemUOpType.mem_SW.asUInt -> "b1111".U(4.W)
  ))

  private val wdata_shifted = MuxLookup(op_reg.asUInt, wdata_reg)(Seq(
    MemUOpType.mem_SB.asUInt -> (wdata_reg(7, 0) << (byte_offset << 3.U)),
    MemUOpType.mem_SH.asUInt -> Mux(addr_reg(1), wdata_reg(15, 0) << 16.U, wdata_reg(15, 0)),
    MemUOpType.mem_SW.asUInt -> wdata_reg
  ))

  io.dcache.w.bits.data := wdata_shifted
  io.dcache.w.bits.strb := wstrb
  io.dcache.w.bits.last := true.B

  io.dcache.b.ready := (write_state === WriteState.b_wait)

  io.in.ready := (read_state === ReadState.idle) && (write_state === WriteState.idle)

  private val isReadDone  = (read_state === ReadState.done)
  private val isWriteDone = (write_state === WriteState.done)

  io.out.valid            := isReadDone || isWriteDone
  io.out.bits.rdata       := Mux(isReadDone, rdata_reg, 0.U)
  io.out.bits.exception   := exception_reg
  io.out.bits.exceptionEn := exceptionEn_reg
  io.out.bits.xtval       := addr_reg
}
