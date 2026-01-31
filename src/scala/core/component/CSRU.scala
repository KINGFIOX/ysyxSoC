package ysyx.core.component

import chisel3._
import chisel3.util._
import ysyx.core.common.HasCoreParameter
import ysyx.core.common.HasRegFileParameter
import ysyx.core.common.HasCSRParameter

// 指令执行完成、指令发生了异常, 需要 commit(交付)
class CSRCommitIO extends Bundle with HasCoreParameter {
  val xepc = UInt(XLEN.W); val xepc_wen = Bool()
  val xcause = UInt(XLEN.W); val xcause_wen = Bool()
  val xtval = UInt(XLEN.W); val xtval_wen = Bool()
}

class CSRUDebugBundle extends Bundle with HasCoreParameter with HasCSRParameter {
  val mstatus   = UInt(XLEN.W)
  val mtvec     = UInt(XLEN.W)
  val mepc      = UInt(XLEN.W)
  val mcause    = UInt(XLEN.W)
  val mtval     = UInt(XLEN.W)
  val mvendorid = UInt(XLEN.W)
  val marchid   = UInt(XLEN.W)
}

class CSRU extends Module with HasCoreParameter with HasCSRParameter {
  val io = IO(new Bundle {
    // for `csr instruction`
    val addr = Input(UInt(NRCSRbits.W))
    val wop = Input(CSROpType())
    val wen = Input(Bool())
    val wdata = Input(UInt(XLEN.W)) // rs1_data
    val rdata = Output(UInt(XLEN.W))

    // for `commit`
    val commit = Flipped(new CSRCommitIO)

    // for `exception` and `mret`
    val xepc   = Output(UInt(XLEN.W))
    val xtvec = Output(UInt(XLEN.W))

    // for difftest
    val debug = new CSRUDebugBundle
  })

  // ==================== CSR 寄存器定义 ====================
  // 可读写寄存器
  private val mstatus = RegInit(0x1800.U(XLEN.W)) // TODO: 写入时某些位无效果
  private val mtvec   = RegInit(0.U(XLEN.W))
  private val mepc    = RegInit(0.U(XLEN.W))
  private val mcause  = RegInit(0.U(XLEN.W))
  private val mtval   = RegInit(0.U(XLEN.W))

  // 只读寄存器
  private val mvendorid = 0x79737978.U(XLEN.W) // "ysyx" in ASCII
  private val marchid   = 26010003.U(XLEN.W)

  // ==================== commit ====================
  when(io.commit.xcause_wen) { mcause := io.commit.xcause }
  when(io.commit.xepc_wen) { mepc := io.commit.xepc }
  when(io.commit.xtval_wen) { mtval := io.commit.xtval }

  // ==================== commit ====================
  io.xepc := mepc
  io.xtvec := mtvec

  // ==================== 读取映射表 ====================
  private val csrReadMap = Seq(
    (MSTATUS.U, mstatus),
    (MTVEC.U, mtvec),
    (MEPC.U, mepc),
    (MCAUSE.U, mcause),
    (MTVAL.U, mtval),
    (MVENDORID.U, mvendorid), // mvendorid 地址
    (MARCHID.U, marchid)      // marchid 地址
  )

  // ==================== 读取 CSR() ====================
  private val csrRdata = MuxLookup(io.addr, 0.U)(csrReadMap)
  io.rdata := csrRdata

  // ==================== 计算写入数据(wdata, waddr, wen) ====================
  // CSRRW: wdata = rs1
  // CSRRS: wdata = csr | rs1
  private val csrWdata = MuxCase(
    io.wdata,
    Seq(
      (io.wop === CSROpType.CSR_RW) -> io.wdata,
      (io.wop === CSROpType.CSR_RS) -> (csrRdata | io.wdata)
    )
  )
  when(io.wen) {
    when(io.addr === MSTATUS.U) { mstatus := csrWdata }
    when(io.addr === MTVEC.U) { mtvec := csrWdata }
    when(io.addr === MEPC.U) { mepc := csrWdata }
    when(io.addr === MCAUSE.U) { mcause := csrWdata }
    when(io.addr === MTVAL.U) { mtval := csrWdata }
  }

  // ==================== debug 输出 ====================
  io.debug.mstatus := mstatus
  io.debug.mtvec := mtvec
  io.debug.mepc := mepc
  io.debug.mcause := mcause
  io.debug.mtval := mtval
  io.debug.mvendorid := mvendorid
  io.debug.marchid   := marchid
}
