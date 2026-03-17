package ysyx.core.backend

import chisel3._
import chisel3.util._

import ysyx.core.common._

// from the master(rob)'s point-of-view
class CsrExceptWritePort extends Bundle with HasCoreParameter {
  val xepc = UInt(dataBits.W)
  val xepc_wen = Bool()
  val xcause = UInt(dataBits.W)
  val xcause_wen = Bool()
  val xtval = UInt(dataBits.W)
  val xtval_wen = Bool()
}

class CsrWriteOnlyPort extends CsrRobEntry {
  val wen = Bool()
  val result = Input(UInt(dataBits.W))
}

class CSRUDebugBundle
    extends Bundle
    with HasCoreParameter
    with HasCSRParameter {
  val mstatus = UInt(dataBits.W)
  val mtvec = UInt(dataBits.W)
  val mepc = UInt(dataBits.W)
  val mcause = UInt(dataBits.W)
  val mtval = UInt(dataBits.W)
  val mvendorid = UInt(dataBits.W)
  val marchid = UInt(dataBits.W)
}

class CSRU extends LateExecUnit(new CsrWriteOnlyPort) {
  val probe = IO(new CSRUDebugBundle)
  val except = IO(Flipped(new CsrExceptWritePort)) // for exception
  val xepc = IO(UInt(dataBits.W)) // for `mret`
  val xtvec = IO(UInt(dataBits.W)) // for `ecall`

  late.done := late.req
  late.bits.result := 0.U

  // readable && writable register
  val mstatus = RegInit(0x1800.U(dataBits.W)) // TODO: write any, read legal
  val mtvec = RegInit(0.U(dataBits.W))
  val mepc = RegInit(0.U(dataBits.W))
  val mcause = RegInit(0.U(dataBits.W))
  val mtval = RegInit(0.U(dataBits.W))

  // read only register
  val mvendorid = 0x7973_7978.U(dataBits.W) // "ysyx" in ASCII
  val marchid = 26010003.U(dataBits.W)

  when(except.xcause_wen) { mcause := except.xcause }
  when(except.xepc_wen) { mepc := except.xepc }
  when(except.xtval_wen) { mtval := except.xtval }

  xepc := mepc
  xtvec := mtvec

  // map of csr
  private val csr_map = Seq(
    (MSTATUS.U, mstatus),
    (MTVEC.U, mtvec),
    (MEPC.U, mepc),
    (MCAUSE.U, mcause),
    (MTVAL.U, mtval),
    (MVENDORID.U, mvendorid), // mvendorid 地址
    (MARCHID.U, marchid) // marchid 地址
  )

  // read
  val csr_read = MuxLookup(late.bits.addr, 0.U)(csr_map)
  late.bits.result := csr_read

  // calculate results
  // CSRRW: wdata = rs1
  // CSRRS: wdata = csr | rs1
  private val csrWdata = MuxCase(
    late.bits.wdata,
    Seq(
      (late.bits.op === CSROpType.CSR_RW) -> late.bits.wdata,
      (late.bits.op === CSROpType.CSR_RS) -> (csr_read | late.bits.wdata)
    )
  )
  when(late.bits.wen) {
    when(late.bits.addr === MSTATUS.U) { mstatus := csrWdata }
    when(late.bits.addr === MTVEC.U) { mtvec := csrWdata }
    when(late.bits.addr === MEPC.U) { mepc := csrWdata }
    when(late.bits.addr === MCAUSE.U) { mcause := csrWdata }
    when(late.bits.addr === MTVAL.U) { mtval := csrWdata }
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
  probe := csrDebugBundle
}
