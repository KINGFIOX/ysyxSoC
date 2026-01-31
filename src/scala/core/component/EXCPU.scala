package ysyx.core.component

import chisel3._
import chisel3.util._
import ysyx.core.common.HasCoreParameter
import ysyx.core.dpi.ExceptionDpi

class EXCPUOutputBundle extends Bundle with HasCoreParameter {
  val mcause = UInt(XLEN.W)
  val mtval  = UInt(XLEN.W)
}

class EXCPUInputBundle extends Bundle with HasCoreParameter {
  val ifu = IFUExceptionType(); val ifuEn = Bool(); val ifuXtval = UInt(XLEN.W)
  val cu = CUExceptionType(); val cuEn = Bool(); val cuXtval = UInt(XLEN.W)
  val lsu = MemUExceptionType(); val lsuEn = Bool(); val lsuXtval = UInt(XLEN.W)
  val a0 = UInt(XLEN.W)
  val pc = UInt(XLEN.W)
}


class EXCPU extends Module {
  val io = IO(new Bundle {
    val in = Flipped(DecoupledIO(new EXCPUInputBundle))
    val out = DecoupledIO(new EXCPUOutputBundle)
  })

  io.in.ready := true.B

  private val ins = io.in.bits

  /* ========== exception 与 mcause 的映射 ========== */
  private val ifuExceptionMcauseMap = Seq(
    IFUExceptionType.ifu_INSTRUCTION_ADDRESS_MISALIGNED -> 0.U,
    IFUExceptionType.ifu_INSTRUCTION_ACCESS_FAULT       -> 1.U,
    IFUExceptionType.ifu_INSTRUCTION_PAGE_FAULT         -> 12.U
  )
  private val cuExceptionMcauseMap = Seq(
    CUExceptionType.cu_ILLEGAL_INSTRUCTION  -> 2.U,
    CUExceptionType.cu_BREAKPOINT           -> 3.U,
    CUExceptionType.cu_ECALL_FROM_U_MODE    -> 8.U,
    CUExceptionType.cu_ECALL_FROM_S_MODE    -> 9.U,
    CUExceptionType.cu_ECALL_FROM_M_MODE    -> 11.U
  )
  private val lsuExceptionMcauseMap = Seq(
    MemUExceptionType.mem_LOAD_ADDRESS_MISALIGNED  -> 4.U,
    MemUExceptionType.mem_LOAD_ACCESS_FAULT        -> 5.U,
    MemUExceptionType.mem_STORE_ADDRESS_MISALIGNED -> 6.U,
    MemUExceptionType.mem_STORE_ACCESS_FAULT       -> 7.U,
    MemUExceptionType.mem_LOAD_PAGE_FAULT          -> 13.U,
    MemUExceptionType.mem_STORE_PAGE_FAULT         -> 15.U
  )

  // IFU > CU > LSU
  private val mcause = MuxCase(0.U, Seq(
    ins.ifuEn -> MuxLookup(ins.ifu, 0.U)(ifuExceptionMcauseMap.map { case (k, v) => k -> v }),
    ins.cuEn  -> MuxLookup(ins.cu, 0.U)(cuExceptionMcauseMap.map { case (k, v) => k -> v }),
    ins.lsuEn -> MuxLookup(ins.lsu, 0.U)(lsuExceptionMcauseMap.map { case (k, v) => k -> v })
  ))
  private val hasException = ins.ifuEn || ins.cuEn || ins.lsuEn

  private val mtval = MuxCase(0.U, Seq(
    ins.ifuEn -> io.in.bits.ifuXtval,
    ins.cuEn -> io.in.bits.cuXtval,
    ins.lsuEn -> io.in.bits.lsuXtval,
  ))

  // DPI: 异常处理 (ebreak 时触发)
  ExceptionDpi(ins.cuEn && (ins.cu === CUExceptionType.cu_BREAKPOINT), ins.pc, mcause, ins.a0, mtval)

  // 输出
  io.out.valid := hasException
  io.out.bits.mcause := mcause
  io.out.bits.mtval := mtval
}
