package ysyx.core.backend

import chisel3._
import chisel3.util._

import ysyx.core.common._
import ysyx.core.frontend._

class DebugCommitBundle extends NPCBundle {
  val pc = UInt(dataBits.W)
  val dnpc = UInt(dataBits.W)
  val inst = UInt(instBits.W)
  val is_mmio = Bool()
  // val has_except = Bool()
}

class CommitStage extends NPCModule {
  val rfu_w = IO(Valid(new RFUWritePort))
  val rat = IO(Valid(new RATCommitPort))
  val csr = IO(new Bundle {
    val except = Valid(new CsrExceptWritePort)
    val xepc = Input(UInt(dataBits.W))
    val xtvec = Input(UInt(dataBits.W))
  })
  val rob = IO(Flipped(ReqDone(new CommitBundle)))
  val redirect = IO(Valid(new RedirectBundle))
  val cdb = IO(Valid(new CDBBundle))
  val flush = IO(Bool())
  val fence_i = IO(Bool())

  // ---- Head entry aliases ----
  val head_entry = rob.bits.entry
  val head_tag = rob.bits.tag
  val head_valid = rob.req

  val rob_rd_wen = head_entry.rd.state === RdRobState.done
  assert(
    // done -> !x0
    !(head_entry.rd.state === RdRobState.done) || (head_entry.rd.idx =/= 0.U),
    "(commit) impossible"
  )
  val head_is_mem = head_entry.mem.r_en || head_entry.mem.w_en
  val head_is_csr = head_entry.inst_type === InstType.CSR
  val head_is_mret = head_entry.inst_type === InstType.MRET
  // is control flow instruction, but not `mret` or `ecall`

  // ---- Defaults ----
  cdb.valid := false.B
  cdb.bits.tag := head_tag
  cdb.bits.value := head_entry.rd.value

  rob.done := head_valid

  // regfile unit
  rfu_w.valid := false.B
  rfu_w.bits.addr := head_entry.rd.idx
  rfu_w.bits.data := head_entry.rd.value

  // register alias table
  rat.valid := false.B
  rat.bits.addr := head_entry.rd.idx
  rat.bits.tag := head_tag

  csr.except.valid := false.B
  csr.except.bits.xepc := head_entry.pc
  csr.except.bits.xcause := head_entry.except.mcause
  csr.except.bits.xtval := head_entry.except.mtval

  fence_i := false.B

  val dbg_is_mmio = WireDefault(false.B)

  redirect.valid := rob.fire
  redirect.bits.wrong_pc := head_entry.pc
  redirect.bits.inst_type := head_entry.inst_type
  redirect.bits.is_call := head_entry.jal.is_call
  redirect.bits.is_ret := head_entry.jalr.is_ret
  redirect.bits.dnpc := MuxCase(
    head_entry.predict_npc,
    Seq(
      (head_entry.inst_type === InstType.JAL) -> head_entry.jal.dnpc,
      (head_entry.inst_type === InstType.JALR) -> head_entry.jalr.dnpc,
      (head_entry.inst_type === InstType.BRANCH) -> Mux( head_entry.bru.br_flag, head_entry.bru.dnpc, head_entry.bru.snpc),
      (head_entry.inst_type === InstType.MRET) -> csr.xepc,
      (head_entry.except.valid) -> csr.xtvec
    )
  )
  redirect.bits.mispredict := redirect.bits.dnpc =/= head_entry.predict_npc
  flush := rob.fire && redirect.bits.mispredict

  // ---- Commit logic ----
  when(head_valid) {
    when(head_entry.except.valid) { // except happen
      csr.except.valid := true.B
    }.elsewhen(head_is_mret) { // mret
    }.otherwise {
      rfu_w.valid := rob_rd_wen
      rat.valid := rob_rd_wen
      cdb.valid := rob_rd_wen && (head_is_mem || head_is_csr) // cdb
      dbg_is_mmio := head_is_mem && head_entry.mem.is_mmio // debug commit mmio

    }
  }

  // sequential sync: delay 1 cycle
  // for writing the register could be read
  val probe = IO(Valid(new DebugCommitBundle))
  probe.valid := RegNext(rob.fire)
  probe.bits.pc := RegNext(head_entry.pc)
  probe.bits.dnpc := RegNext(redirect.bits.dnpc)
  probe.bits.inst := RegNext(head_entry.inst)
  probe.bits.is_mmio := RegNext(dbg_is_mmio)
}
