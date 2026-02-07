package ysyx.core.component

import chisel3._
import chisel3.util._
import ysyx.core.common.HasCoreParameter
import freechips.rocketchip.amba.axi4._
import ysyx.CPUAXI4BundleParameters
import ysyx.SoCConfig

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
  private val (ar, r, aw, w, b) = {
    val in = io.dcache
    (in.ar, in.r, in.aw, in.w, in.b)
  }

  private val isLoad  = io.in.bits.op.isOneOf(MemUOpType.mem_LB, MemUOpType.mem_LH, MemUOpType.mem_LW, MemUOpType.mem_LBU, MemUOpType.mem_LHU)
  private val isStore = io.in.bits.op.isOneOf(MemUOpType.mem_SB, MemUOpType.mem_SH, MemUOpType.mem_SW)

  private val addr = io.in.bits.addr
  private val addrAlign2 = addr(0) === 0.U
  private val addrAlign4 = addr(1, 0) === 0.U

  // 判断是否需要窄传输（UART等设备需要使用实际的size而非4字节对齐传输）
  private val isNarrowDevice = (addr >= SoCConfig.uartBase.U) && (addr < (SoCConfig.uartBase + SoCConfig.uartSize).U)

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
  private val isNarrowDevice_reg = RegInit(false.B)

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
        isNarrowDevice_reg := isNarrowDevice
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
      when(ar.fire) { read_state := ReadState.r_wait }
    }
    is(ReadState.r_wait) {
      when(r.fire) {
        when(r.bits.resp =/= AXI4Resp.OKAY) {
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
        isNarrowDevice_reg := isNarrowDevice
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
      when(aw.fire) { aw_sent := true.B }
      when(w.fire) { w_sent := true.B }
      val aw_done = aw_sent || aw.fire
      val w_done  = w_sent || w.fire
      when(aw_done && w_done) { write_state := WriteState.b_wait }
    }
    is(WriteState.b_wait) {
      when(b.fire) {
        when(b.bits.resp =/= AXI4Resp.OKAY) {
          exception_reg := MemUExceptionType.mem_STORE_ACCESS_FAULT
          exceptionEn_reg := true.B
        }
        write_state := WriteState.done
      }
    }
    is(WriteState.done) {
      when(io.out.fire) { write_state := WriteState.idle }
    }
  }

  // AR channel
  ar.valid      := (read_state === ReadState.ar_wait) && !loadMisaligned /*需要!loadMisaligned, 因为不能拉低valid信号*/
  ar.bits.id    := 0.U
  ar.bits.burst := 1.U
  ar.bits.lock  := 0.U
  ar.bits.cache := 0.U
  ar.bits.prot  := 0.U
  ar.bits.qos   := 0.U

  // 窄传输：使用实际的size和地址
  // 宽传输：统一使用32位读取，从返回的word中按byte_offset提取数据
  private val ar_size_narrow = MuxLookup(op_reg.asUInt, 2.U)(Seq(
    MemUOpType.mem_LB.asUInt  -> 0.U,
    MemUOpType.mem_LBU.asUInt -> 0.U,
    MemUOpType.mem_LH.asUInt  -> 1.U,
    MemUOpType.mem_LHU.asUInt -> 1.U,
    MemUOpType.mem_LW.asUInt  -> 2.U
  ))
  private val ar_addr_narrow = MuxLookup(op_reg.asUInt, addr_reg)(Seq(
    MemUOpType.mem_LB.asUInt  -> addr_reg,
    MemUOpType.mem_LBU.asUInt -> addr_reg,
    MemUOpType.mem_LH.asUInt  -> Cat(addr_reg(XLEN - 1, 1), 0.U(1.W)),
    MemUOpType.mem_LHU.asUInt -> Cat(addr_reg(XLEN - 1, 1), 0.U(1.W)),
    MemUOpType.mem_LW.asUInt  -> Cat(addr_reg(XLEN - 1, 2), 0.U(2.W))
  ))
  private val ar_size_wide = 2.U
  private val ar_addr_wide = Cat(addr_reg(XLEN - 1, 2), 0.U(2.W))

  private val ar_size = Mux(isNarrowDevice_reg, ar_size_narrow, ar_size_wide)
  private val ar_addr = Mux(isNarrowDevice_reg, ar_addr_narrow, ar_addr_wide)

  ar.bits.addr := ar_addr
  ar.bits.len  := 0.U // 2^0=1
  ar.bits.size := ar_size

  r.ready := (read_state === ReadState.r_wait)

  private val rdata_raw = r.bits.data
  private val rdata_reg = RegInit(0.U(XLEN.W))

  private val byte_offset = addr_reg(1, 0)
  
  // 宽传输：从返回的word中按byte_offset提取数据
  private val rdata_byte_wide = (rdata_raw >> (byte_offset << 3.U))(7, 0)
  private val rdata_half_wide = Mux(addr_reg(1), rdata_raw(31, 16), rdata_raw(15, 0))
  
  // 窄传输：数据已经在低位
  private val rdata_byte_narrow = rdata_raw(7, 0)
  private val rdata_half_narrow = rdata_raw(15, 0)
  
  private val rdata_byte = Mux(isNarrowDevice_reg, rdata_byte_narrow, rdata_byte_wide)
  private val rdata_half = Mux(isNarrowDevice_reg, rdata_half_narrow, rdata_half_wide)
  
  when(r.fire) {
    rdata_reg := MuxLookup(op_reg.asUInt, rdata_raw)(Seq(
      MemUOpType.mem_LB.asUInt  -> SignExt(rdata_byte, XLEN),
      MemUOpType.mem_LBU.asUInt -> ZeroExt(rdata_byte, XLEN),
      MemUOpType.mem_LH.asUInt  -> SignExt(rdata_half, XLEN),
      MemUOpType.mem_LHU.asUInt -> ZeroExt(rdata_half, XLEN),
      MemUOpType.mem_LW.asUInt  -> rdata_raw
    ))
  }

  // AW channel
  // 窄传输：使用实际的size和地址
  // 宽传输：统一使用32位写入，用strb选择要写的字节
  private val aw_size_narrow = MuxLookup(op_reg.asUInt, 2.U)(Seq(
    MemUOpType.mem_SB.asUInt -> 0.U,
    MemUOpType.mem_SH.asUInt -> 1.U,
    MemUOpType.mem_SW.asUInt -> 2.U
  ))
  private val aw_addr_narrow = MuxLookup(op_reg.asUInt, addr_reg)(Seq(
    MemUOpType.mem_SB.asUInt -> addr_reg,
    MemUOpType.mem_SH.asUInt -> Cat(addr_reg(XLEN - 1, 1), 0.U(1.W)),
    MemUOpType.mem_SW.asUInt -> Cat(addr_reg(XLEN - 1, 2), 0.U(2.W))
  ))
  private val aw_size_wide = 2.U
  private val aw_addr_wide = Cat(addr_reg(XLEN - 1, 2), 0.U(2.W))

  private val aw_size = Mux(isNarrowDevice_reg, aw_size_narrow, aw_size_wide)
  private val aw_addr = Mux(isNarrowDevice_reg, aw_addr_narrow, aw_addr_wide)

  aw.valid      := (write_state === WriteState.aw_w_wait) && !aw_sent && !storeMisaligned /*需要!storeMisaligned, 因为不能拉低valid信号*/
  aw.bits.id    := 0.U
  aw.bits.addr  := aw_addr
  aw.bits.len   := 0.U
  aw.bits.size  := aw_size
  aw.bits.burst := 1.U
  aw.bits.lock  := 0.U
  aw.bits.cache := 0.U
  aw.bits.prot  := 0.U
  aw.bits.qos   := 0.U

  // W channel
  w.valid := (write_state === WriteState.aw_w_wait) && !w_sent

  // 宽传输：数据移位到对应位置，strb选择要写的字节
  private val wstrb_wide = MuxLookup(op_reg.asUInt, "b1111".U(4.W))(Seq(
    MemUOpType.mem_SB.asUInt -> ("b0001".U << byte_offset),
    MemUOpType.mem_SH.asUInt -> Mux(addr_reg(1), "b1100".U(4.W), "b0011".U(4.W)),
    MemUOpType.mem_SW.asUInt -> "b1111".U(4.W)
  ))
  private val wdata_wide = MuxLookup(op_reg.asUInt, wdata_reg)(Seq(
    MemUOpType.mem_SB.asUInt -> (wdata_reg(7, 0) << (byte_offset << 3.U)),
    MemUOpType.mem_SH.asUInt -> Mux(addr_reg(1), wdata_reg(15, 0) << 16.U, wdata_reg(15, 0)),
    MemUOpType.mem_SW.asUInt -> wdata_reg
  ))

  private val wstrb_narrow = MuxLookup(op_reg.asUInt, "b1111".U(4.W))(Seq(
    MemUOpType.mem_SB.asUInt -> "b0001".U(4.W),
    MemUOpType.mem_SH.asUInt -> "b0011".U(4.W),
    MemUOpType.mem_SW.asUInt -> "b1111".U(4.W)
  ))
  private val wdata_narrow = MuxLookup(op_reg.asUInt, wdata_reg)(Seq(
    MemUOpType.mem_SB.asUInt -> wdata_reg(7, 0),
    MemUOpType.mem_SH.asUInt -> wdata_reg(15, 0),
    MemUOpType.mem_SW.asUInt -> wdata_reg
  ))

  private val wstrb = Mux(isNarrowDevice_reg, wstrb_narrow, wstrb_wide)
  private val wdata_shifted = Mux(isNarrowDevice_reg, wdata_narrow, wdata_wide)

  w.bits.data := wdata_shifted
  w.bits.strb := wstrb
  w.bits.last := true.B

  b.ready := (write_state === WriteState.b_wait)

  io.in.ready := (read_state === ReadState.idle) && (write_state === WriteState.idle)

  private val isReadDone  = (read_state === ReadState.done)
  private val isWriteDone = (write_state === WriteState.done)

  io.out.valid            := isReadDone || isWriteDone
  io.out.bits.rdata       := Mux(isReadDone, rdata_reg, 0.U)
  io.out.bits.exception   := exception_reg
  io.out.bits.exceptionEn := exceptionEn_reg
  io.out.bits.xtval       := addr_reg
}
