package ysyx.core.backend

import chisel3._
import chisel3.util._

import ysyx.core.common._
import ysyx.core.frontend._

class DebugCommitBundle extends NPCBundle {
  val valid = Bool()
  val pc = UInt(dataBits.W)
  val dnpc = UInt(dataBits.W)
  val inst = UInt(instBits.W)
  val is_mmio = Bool()
  // val has_except = Bool()
}

class CommitStage extends NPCModule {
  val rfu_w = IO(new RFUWritePort)
  val rat = IO(new RATCommitPort)
  val csr = IO(new Bundle {
    val except = new CsrExceptWritePort
    val xepc = Input(UInt(dataBits.W))
    val xtvec = Input(UInt(dataBits.W))
  })
  val probe = IO(new DebugCommitBundle)
  val rob = IO(Flipped(ReqDone(new CommitBundle)))
  val flush = IO(Bool())
  val ifu = IO(new RedirectBundle)
  val cdb = IO(new CDBBundle)
  val fence_i = IO(Bool())

  // ---- Head entry aliases ----
  val head = rob.bits.entry
  val head_tag = rob.bits.tag
  val head_valid = rob.req

  val rd_def = head.rd.idx =/= 0.U
  val head_is_mem = head.mem.r_en || head.mem.w_en
  val head_is_csr = head.inst_type === InstType.CSR
  val head_is_mret = head.inst_type === InstType.MRET

  // is control flow instruction, but not `mret` or `ecall`
  val is_control_normal = Seq(InstType.JAL, InstType.JALR, InstType.BRANCH)
    .map(head.inst_type === _)
    .reduce(_ || _)
  // format: off
  val actual_npc = MuxCase(
    head.pc + 4.U,
    Seq(
      (head.inst_type === InstType.JAL) -> head.jal.dnpc,
      (head.inst_type === InstType.JALR) -> head.jalr.dnpc,
      (head.inst_type === InstType.BRANCH) -> Mux( head.bru.br_flag, head.bru.dnpc, head.bru.snpc)
    )
  )
  // format: on
  val mispredict = is_control_normal && (actual_npc =/= head.predict_npc)

  // ---- Defaults ----
  flush := false.B
  ifu.valid := false.B
  ifu.correct_npc := 0.U
  ifu.wrong_pc := 0.U

  cdb.valid := false.B
  cdb.tag := head_tag
  cdb.value := 0.U

  rob.done := false.B

  rfu_w.en := false.B
  rfu_w.addr := head.rd.idx
  rfu_w.data := head.rd.value

  rat.en := false.B
  rat.addr := head.rd.idx
  rat.tag := head_tag

  csr.except.xepc := head.pc
  csr.except.xepc_wen := false.B
  csr.except.xcause := head.except.mcause
  csr.except.xcause_wen := false.B
  csr.except.xtval := head.except.mtval
  csr.except.xtval_wen := false.B

  fence_i := false.B

  val dbg_valid = WireDefault(false.B)
  val dbg_dnpc = WireDefault(head.pc)
  val dbg_is_mmio = WireDefault(false.B)

  // ---- Commit logic ----
  when(head_valid) {
    when(head.except.valid) { // happen
      csr.except.xepc_wen := true.B
      csr.except.xcause_wen := true.B
      csr.except.xtval_wen := true.B

      flush := true.B
      ifu.valid := true.B
      ifu.correct_npc := csr.xtvec
      ifu.wrong_pc := head.pc

      rob.done := true.B

      dbg_valid := true.B
      dbg_dnpc := csr.xtvec
      dbg_is_mmio := false.B

    }.elsewhen(head_is_mret) { // mret
      flush := true.B
      ifu.valid := true.B
      ifu.correct_npc := csr.xepc
      ifu.wrong_pc := head.pc

      rob.done := true.B

      dbg_valid := true.B
      dbg_dnpc := csr.xepc
      dbg_is_mmio := false.B

    }.otherwise {
      rfu_w.en := rd_def
      rfu_w.data := head.rd.value

      rat.en := rd_def

      cdb.valid := rd_def && (head_is_mem || head_is_csr)
      cdb.value := head.rd.value

      flush := mispredict
      ifu.valid := mispredict
      ifu.correct_npc := actual_npc
      ifu.wrong_pc := head.pc

      rob.done := true.B

      dbg_valid := true.B
      dbg_dnpc := Mux(mispredict, actual_npc, head.pc + 4.U)
      dbg_is_mmio := head_is_mem && head.mem.is_mmio
    }
  }

  // sequential sync: delay 1 cycle
  // for writing the register could be read
  probe.valid := RegNext(dbg_valid)
  probe.pc := RegNext(head.pc)
  probe.dnpc := RegNext(dbg_dnpc)
  probe.inst := RegNext(head.inst)
  probe.is_mmio := RegNext(dbg_is_mmio)
}
