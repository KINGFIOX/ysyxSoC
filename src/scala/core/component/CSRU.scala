package ysyx.core.component

import chisel3._
import chisel3.util._
import ysyx.core.common._
import chisel3.probe.{define, Probe, ProbeValue}

// from the master's point-of-view
class CSRCommitIO extends Bundle with HasCoreParameter {
  val xepc = UInt(dataBits.W); val xepc_wen = Bool()
  val xcause = UInt(dataBits.W); val xcause_wen = Bool()
  val xtval = UInt(dataBits.W); val xtval_wen = Bool()
}

class CSRUDebugBundle extends Bundle with HasCoreParameter with HasCSRParameter {
  val mstatus   = UInt(dataBits.W)
  val mtvec     = UInt(dataBits.W)
  val mepc      = UInt(dataBits.W)
  val mcause    = UInt(dataBits.W)
  val mtval     = UInt(dataBits.W)
  val mvendorid = UInt(dataBits.W)
  val marchid   = UInt(dataBits.W)
}

class CSRU extends Module with HasCoreParameter with HasCSRParameter {
  val io = IO(new Bundle {
    val late = new LateExecIO

    val addr = Input(UInt(NRCSRbits.W))
    val wop = Input(CSROpType())
    val wen = Input(Bool())
    val wdata = Input(UInt(dataBits.W))
    val rdata = Output(UInt(dataBits.W))

    val commit = Flipped(new CSRCommitIO)

    val xepc  = Output(UInt(dataBits.W))
    val xtvec = Output(UInt(dataBits.W))

    val probe = Output(Probe(new CSRUDebugBundle))
  })

  io.late.done         := io.late.req
  io.late.result       := 0.U
  io.late.result_valid := true.B

  // ==================== CSR 寄存器定义 ====================
  // 可读写寄存器
  private val mstatus = RegInit(0x1800.U(dataBits.W)) // TODO: 写入时某些位无效果
  private val mtvec   = RegInit(0.U(dataBits.W))
  private val mepc    = RegInit(0.U(dataBits.W))
  private val mcause  = RegInit(0.U(dataBits.W))
  private val mtval   = RegInit(0.U(dataBits.W))

  // 只读寄存器
  private val mvendorid = 0x79737978.U(dataBits.W) // "ysyx" in ASCII
  private val marchid   = 26010003.U(dataBits.W)

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
  io.late.result := csrRdata

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

  // probe
  val csrDebugBundle = Wire(new CSRUDebugBundle)
  csrDebugBundle.mstatus := mstatus
  csrDebugBundle.mtvec := mtvec
  csrDebugBundle.mepc := mepc
  csrDebugBundle.mcause := mcause
  csrDebugBundle.mtval := mtval
  csrDebugBundle.mvendorid := mvendorid
  csrDebugBundle.marchid := marchid
  define(io.probe, ProbeValue(csrDebugBundle))
}
