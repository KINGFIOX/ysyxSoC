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

// ============================================================
// AXI4 Read Channel Controller
// Handles AR/R channel state machine, address/size generation,
// and read data extraction with sign/zero extension.
// ============================================================
class AXI4ReadPort extends Module with HasCoreParameter {
  private val axiParams = CPUAXI4BundleParameters()
  val io = IO(new Bundle {
    // Control interface
    val start          = Input(Bool())
    val op             = Input(MemUOpType())
    val addr           = Input(UInt(XLEN.W))
    val isNarrowDevice = Input(Bool())
    // Status interface
    val idle           = Output(Bool())
    val done           = Output(Bool())
    val ack            = Input(Bool())
    // Result interface
    val rdata          = Output(UInt(XLEN.W))
    val exception      = Output(MemUExceptionType())
    val exceptionEn    = Output(Bool())
    // AXI4 AR/R channels
    val ar = Irrevocable(new AXI4BundleAR(axiParams))
    val r  = Flipped(Irrevocable(new AXI4BundleR(axiParams)))
  })

  // ---------- State Machine ----------
  object State extends ChiselEnum {
    val idle, ar_wait, r_wait, done = Value
  }
  private val state = RegInit(State.idle)

  // ---------- Latched Inputs ----------
  private val op_reg       = RegInit(MemUOpType.mem_LB)
  private val addr_reg     = RegInit(0.U(XLEN.W))
  private val isNarrow_reg = RegInit(false.B)

  // ---------- Result Registers ----------
  private val rdata_reg       = RegInit(0.U(XLEN.W))
  private val exception_reg   = Reg(MemUExceptionType())
  private val exceptionEn_reg = RegInit(false.B)

  // ---------- State Transitions ----------
  switch(state) {
    is(State.idle) {
      when(io.start) {
        op_reg          := io.op
        addr_reg        := io.addr
        isNarrow_reg    := io.isNarrowDevice
        exceptionEn_reg := false.B // reset status
        state := State.ar_wait
      }
    }
    is(State.ar_wait) {
      when(io.ar.fire) { state := State.r_wait }
    }
    is(State.r_wait) {
      when(io.r.fire) {
        when(io.r.bits.resp =/= AXI4Resp.OKAY) {
          exception_reg   := MemUExceptionType.mem_LOAD_ACCESS_FAULT
          exceptionEn_reg := true.B
        }
        state := State.done
      }
    }
    is(State.done) {
      when(io.ack) { state := State.idle }
    }
  }

  // ---------- AR Channel: Address/Size Generation ----------
  private val byte_offset = addr_reg(1, 0)

  // 窄传输：使用实际的size和地址
  private val ar_size_narrow = MuxLookup(op_reg, 2.U)(Seq(
    MemUOpType.mem_LB  -> 0.U,
    MemUOpType.mem_LBU -> 0.U,
    MemUOpType.mem_LH  -> 1.U,
    MemUOpType.mem_LHU -> 1.U,
    MemUOpType.mem_LW  -> 2.U
  ))
  private val ar_addr_narrow = MuxLookup(op_reg, addr_reg)(Seq(
    MemUOpType.mem_LB  -> addr_reg,
    MemUOpType.mem_LBU -> addr_reg,
    MemUOpType.mem_LH  -> Cat(addr_reg(XLEN - 1, 1), 0.U(1.W)),
    MemUOpType.mem_LHU -> Cat(addr_reg(XLEN - 1, 1), 0.U(1.W)),
    MemUOpType.mem_LW  -> Cat(addr_reg(XLEN - 1, 2), 0.U(2.W))
  ))
  // 宽传输：统一使用32位读取，从返回的word中按byte_offset提取数据
  private val ar_size_wide = 2.U
  private val ar_addr_wide = Cat(addr_reg(XLEN - 1, 2), 0.U(2.W))

  private val ar_size = Mux(isNarrow_reg, ar_size_narrow, ar_size_wide)
  private val ar_addr = Mux(isNarrow_reg, ar_addr_narrow, ar_addr_wide)

  io.ar.valid      := (state === State.ar_wait)
  io.ar.bits.id    := 0.U
  io.ar.bits.addr  := ar_addr
  io.ar.bits.len   := 0.U
  io.ar.bits.size  := ar_size
  io.ar.bits.burst := 1.U
  io.ar.bits.lock  := 0.U
  io.ar.bits.cache := 0.U
  io.ar.bits.prot  := 0.U
  io.ar.bits.qos   := 0.U

  // ---------- R Channel: Data Extraction ----------
  io.r.ready := (state === State.r_wait)

  private val rdata_raw = io.r.bits.data

  // 宽传输：从返回的word中按byte_offset提取数据
  private val rdata_byte_wide = (rdata_raw >> (byte_offset << 3.U))(7, 0)
  private val rdata_half_wide = Mux(addr_reg(1), rdata_raw(31, 16), rdata_raw(15, 0))
  // 窄传输：数据已经在低位
  private val rdata_byte_narrow = rdata_raw(7, 0)
  private val rdata_half_narrow = rdata_raw(15, 0)

  private val rdata_byte = Mux(isNarrow_reg, rdata_byte_narrow, rdata_byte_wide)
  private val rdata_half = Mux(isNarrow_reg, rdata_half_narrow, rdata_half_wide)

  when(io.r.fire) {
    rdata_reg := MuxLookup(op_reg, rdata_raw)(Seq(
      MemUOpType.mem_LB  -> SignExt(rdata_byte, XLEN),
      MemUOpType.mem_LBU -> ZeroExt(rdata_byte, XLEN),
      MemUOpType.mem_LH  -> SignExt(rdata_half, XLEN),
      MemUOpType.mem_LHU -> ZeroExt(rdata_half, XLEN),
      MemUOpType.mem_LW  -> rdata_raw
    ))
  }

  // ---------- Status/Result Outputs ----------
  io.idle        := (state === State.idle)
  io.done        := (state === State.done)
  io.rdata       := rdata_reg
  io.exception   := exception_reg
  io.exceptionEn := exceptionEn_reg
}

// ============================================================
// AXI4 Write Channel Controller
// Handles AW/W/B channel state machine, address/size generation,
// write data shifting and strobe generation.
// ============================================================
class AXI4WritePort extends Module with HasCoreParameter {
  private val axiParams = CPUAXI4BundleParameters()
  val io = IO(new Bundle {
    // Control interface
    val start          = Input(Bool())
    val op             = Input(MemUOpType())
    val addr           = Input(UInt(XLEN.W))
    val wdata          = Input(UInt(XLEN.W))
    val isNarrowDevice = Input(Bool())
    // Status interface
    val idle           = Output(Bool())
    val done           = Output(Bool())
    val ack            = Input(Bool())
    // Result interface
    val exception      = Output(MemUExceptionType())
    val exceptionEn    = Output(Bool())
    // AXI4 AW/W/B channels
    val aw = Irrevocable(new AXI4BundleAW(axiParams))
    val w  = Irrevocable(new AXI4BundleW(axiParams))
    val b  = Flipped(Irrevocable(new AXI4BundleB(axiParams)))
  })

  // ---------- State Machine ----------
  object State extends ChiselEnum {
    val idle, aw_w_wait, b_wait, done = Value
  }
  private val state = RegInit(State.idle)

  // ---------- Latched Inputs ----------
  private val op_reg       = RegInit(MemUOpType.mem_SB)
  private val addr_reg     = RegInit(0.U(XLEN.W))
  private val wdata_reg    = RegInit(0.U(XLEN.W))
  private val isNarrow_reg = RegInit(false.B)

  // ---------- Result Registers ----------
  private val exception_reg   = Reg(MemUExceptionType())
  private val exceptionEn_reg = RegInit(false.B)

  // ---------- AW/W Handshake Tracking ----------
  private val aw_sent = RegInit(false.B)
  private val w_sent  = RegInit(false.B)

  // ---------- State Transitions ----------
  switch(state) {
    is(State.idle) {
      when(io.start) {
        op_reg          := io.op
        addr_reg        := io.addr
        wdata_reg       := io.wdata
        isNarrow_reg    := io.isNarrowDevice
        exceptionEn_reg := false.B // reset status
        aw_sent := false.B
        w_sent  := false.B
        state := State.aw_w_wait
      }
    }
    is(State.aw_w_wait) {
      when(io.aw.fire) { aw_sent := true.B }
      when(io.w.fire)  { w_sent  := true.B }
      val aw_done = aw_sent || io.aw.fire
      val w_done  = w_sent  || io.w.fire
      when(aw_done && w_done) { state := State.b_wait }
    }
    is(State.b_wait) {
      when(io.b.fire) {
        when(io.b.bits.resp =/= AXI4Resp.OKAY) {
          exception_reg   := MemUExceptionType.mem_STORE_ACCESS_FAULT
          exceptionEn_reg := true.B
        }
        state := State.done
      }
    }
    is(State.done) {
      when(io.ack) { state := State.idle }
    }
  }

  // ---------- AW Channel: Address/Size Generation ----------
  private val byte_offset = addr_reg(1, 0)

  // 窄传输：使用实际的size和地址
  private val aw_size_narrow = MuxLookup(op_reg, 2.U)(Seq(
    MemUOpType.mem_SB -> 0.U,
    MemUOpType.mem_SH -> 1.U,
    MemUOpType.mem_SW -> 2.U
  ))
  private val aw_addr_narrow = MuxLookup(op_reg, addr_reg)(Seq(
    MemUOpType.mem_SB -> addr_reg,
    MemUOpType.mem_SH -> Cat(addr_reg(XLEN - 1, 1), 0.U(1.W)),
    MemUOpType.mem_SW -> Cat(addr_reg(XLEN - 1, 2), 0.U(2.W))
  ))
  // 宽传输：统一使用32位写入，用strb选择要写的字节
  private val aw_size_wide = 2.U
  private val aw_addr_wide = Cat(addr_reg(XLEN - 1, 2), 0.U(2.W))

  private val aw_size = Mux(isNarrow_reg, aw_size_narrow, aw_size_wide)
  private val aw_addr = Mux(isNarrow_reg, aw_addr_narrow, aw_addr_wide)

  io.aw.valid      := (state === State.aw_w_wait) && !aw_sent
  io.aw.bits.id    := 0.U
  io.aw.bits.addr  := aw_addr
  io.aw.bits.len   := 0.U
  io.aw.bits.size  := aw_size
  io.aw.bits.burst := 1.U
  io.aw.bits.lock  := 0.U
  io.aw.bits.cache := 0.U
  io.aw.bits.prot  := 0.U
  io.aw.bits.qos   := 0.U

  // ---------- W Channel: Data/Strb Generation ----------
  // 宽传输：数据移位到对应位置，strb选择要写的字节
  private val wstrb_wide = MuxLookup(op_reg, "b1111".U(4.W))(Seq(
    MemUOpType.mem_SB -> ("b0001".U << byte_offset),
    MemUOpType.mem_SH -> Mux(addr_reg(1), "b1100".U(4.W), "b0011".U(4.W)),
    MemUOpType.mem_SW -> "b1111".U(4.W)
  ))
  private val wdata_wide = MuxLookup(op_reg, wdata_reg)(Seq(
    MemUOpType.mem_SB -> (wdata_reg(7, 0) << (byte_offset << 3.U)),
    MemUOpType.mem_SH -> Mux(addr_reg(1), wdata_reg(15, 0) << 16.U, wdata_reg(15, 0)),
    MemUOpType.mem_SW -> wdata_reg
  ))
  // 窄传输：数据在低位，strb直接设置
  private val wstrb_narrow = MuxLookup(op_reg, "b1111".U(4.W))(Seq(
    MemUOpType.mem_SB -> "b0001".U(4.W),
    MemUOpType.mem_SH -> "b0011".U(4.W),
    MemUOpType.mem_SW -> "b1111".U(4.W)
  ))
  private val wdata_narrow = MuxLookup(op_reg, wdata_reg)(Seq(
    MemUOpType.mem_SB -> wdata_reg(7, 0),
    MemUOpType.mem_SH -> wdata_reg(15, 0),
    MemUOpType.mem_SW -> wdata_reg
  ))

  private val wstrb = Mux(isNarrow_reg, wstrb_narrow, wstrb_wide)
  private val wdata_shifted = Mux(isNarrow_reg, wdata_narrow, wdata_wide)

  io.w.valid     := (state === State.aw_w_wait) && !w_sent
  io.w.bits.data := wdata_shifted
  io.w.bits.strb := wstrb
  io.w.bits.last := true.B

  // ---------- B Channel ----------
  io.b.ready := (state === State.b_wait)

  // ---------- Status/Result Outputs ----------
  io.idle        := (state === State.idle)
  io.done        := (state === State.done)
  io.exception   := exception_reg
  io.exceptionEn := exceptionEn_reg
}

// ============================================================
// LSU (Top Level)
// Orchestrates read/write sub-modules, handles DecoupledIO
// handshaking, alignment checks, and narrow device detection.
// External interface is unchanged from the original LSU.
// ============================================================
class LSU extends Module with HasCoreParameter {
  val io = IO(new Bundle {
    val in     = Flipped(DecoupledIO(new MEMUInputBundle))
    val out    = DecoupledIO(new MEMUOutputBundle)
    val dcache = AXI4Bundle(CPUAXI4BundleParameters())
  })

  // ---------- Sub-modules ----------
  private val readPort  = Module(new AXI4ReadPort)
  private val writePort = Module(new AXI4WritePort)

  // ---------- Input Decode ----------
  private val isLoad  = io.in.bits.op.isOneOf(MemUOpType.mem_LB, MemUOpType.mem_LH, MemUOpType.mem_LW, MemUOpType.mem_LBU, MemUOpType.mem_LHU)
  private val isStore = io.in.bits.op.isOneOf(MemUOpType.mem_SB, MemUOpType.mem_SH, MemUOpType.mem_SW)

  // ---------- Narrow Device Detection ----------
  private val addr = io.in.bits.addr
  private val isNarrowDevice = (SoCConfig.uartBase.U <= addr) && (addr < (SoCConfig.uartBase + SoCConfig.uartSize).U) ||
    (SoCConfig.spiCtrlBase.U <= addr) && (addr < (SoCConfig.spiCtrlBase + SoCConfig.spiCtrlSize).U)

  // ---------- Alignment Check ----------
  // 窄传输设备使用实际 size/addr，不需要对齐检查
  private val addrAlign2 = addr(0) === 0.U
  private val addrAlign4 = addr(1, 0) === 0.U

  private val loadMisaligned = !isNarrowDevice && MuxLookup(io.in.bits.op, false.B)(Seq(
    MemUOpType.mem_LH  -> !addrAlign2,
    MemUOpType.mem_LHU -> !addrAlign2,
    MemUOpType.mem_LW  -> !addrAlign4
  ))
  private val storeMisaligned = !isNarrowDevice && MuxLookup(io.in.bits.op, false.B)(Seq(
    MemUOpType.mem_SH -> !addrAlign2,
    MemUOpType.mem_SW -> !addrAlign4
  ))

  // ---------- Top-level State Machine ----------
  object State extends ChiselEnum {
    val idle, reading, writing, exception_done = Value
  }
  private val state = RegInit(State.idle)

  // Registers for misaligned exception (used only in exception_done state)
  private val exception_reg   = Reg(MemUExceptionType())
  private val exceptionEn_reg = RegInit(false.B)
  private val addr_reg        = RegInit(0.U(XLEN.W))

  switch(state) {
    is(State.idle) {
      exceptionEn_reg := false.B // reset status
      when(io.in.fire && io.in.bits.en) {
        addr_reg := io.in.bits.addr
        when(isLoad) {
          when(loadMisaligned) {
            exception_reg   := MemUExceptionType.mem_LOAD_ADDRESS_MISALIGNED
            exceptionEn_reg := true.B
            state := State.exception_done
          }.otherwise {
            state := State.reading
          }
        }.elsewhen(isStore) {
          when(storeMisaligned) {
            exception_reg   := MemUExceptionType.mem_STORE_ADDRESS_MISALIGNED
            exceptionEn_reg := true.B
            state := State.exception_done
          }.otherwise {
            state := State.writing
          }
        }
      }
    }
    is(State.reading) {
      when(readPort.io.done && io.out.fire) {
        state := State.idle
      }
    }
    is(State.writing) {
      when(writePort.io.done && io.out.fire) {
        state := State.idle
      }
    }
    is(State.exception_done) { // just for misaligned
      when(io.out.fire) {
        state := State.idle
      }
    }
  }

  // ---------- Input Handshake ----------
  io.in.ready := (state === State.idle)

  // ---------- Sub-module Control ----------
  readPort.io.start          := io.in.fire && isLoad && io.in.bits.en && !loadMisaligned
  readPort.io.op             := io.in.bits.op
  readPort.io.addr           := io.in.bits.addr
  readPort.io.isNarrowDevice := isNarrowDevice
  readPort.io.ack            := io.out.fire && (state === State.reading)

  writePort.io.start          := io.in.fire && isStore && io.in.bits.en && !storeMisaligned
  writePort.io.op             := io.in.bits.op
  writePort.io.addr           := io.in.bits.addr
  writePort.io.wdata          := io.in.bits.wdata
  writePort.io.isNarrowDevice := isNarrowDevice
  writePort.io.ack            := io.out.fire && (state === State.writing)

  // ---------- AXI4 Channel Connections ----------
  io.dcache.ar <> readPort.io.ar
  io.dcache.r  <> readPort.io.r
  io.dcache.aw <> writePort.io.aw
  io.dcache.w  <> writePort.io.w
  io.dcache.b  <> writePort.io.b

  // ---------- Output ----------
  private val isReadDone      = (state === State.reading) && readPort.io.done
  private val isWriteDone     = (state === State.writing) && writePort.io.done
  private val isExceptionDone = (state === State.exception_done)

  io.out.valid            := isReadDone || isWriteDone || isExceptionDone
  io.out.bits.rdata       := Mux(isReadDone, readPort.io.rdata, 0.U)
  io.out.bits.exception   := MuxCase(exception_reg, Seq(
    isReadDone  -> readPort.io.exception,
    isWriteDone -> writePort.io.exception
  ))
  io.out.bits.exceptionEn := MuxCase(exceptionEn_reg, Seq(
    isReadDone  -> readPort.io.exceptionEn,
    isWriteDone -> writePort.io.exceptionEn
  ))
  io.out.bits.xtval       := addr_reg
}
