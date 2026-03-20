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
  val head = rob.bits.entry
  val head_tag = rob.bits.tag
  val head_valid = rob.req

  val rob_rd_wen = head.rd.state === RdRobState.done
  assert(
    // done -> !x0
    !(head.rd.state === RdRobState.done) || (head.rd.idx =/= 0.U),
    "(commit) impossible"
  )
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
      (head.inst_type === InstType.BRANCH) -> Mux(head.bru.br_flag, head.bru.dnpc, head.bru.snpc)
    )
  )
  // format: on
  val mispredict = is_control_normal && (actual_npc =/= head.predict_npc)

  // ---- Defaults ----
  flush := false.B
  redirect.valid := false.B
  redirect.bits.correct_npc := 0.U
  redirect.bits.wrong_pc := 0.U
  redirect.bits.inst_type := head.inst_type
  redirect.bits.is_call := head.jal.is_call
  redirect.bits.is_ret := head.jalr.is_ret

  cdb.valid := false.B
  cdb.bits.tag := head_tag
  cdb.bits.value := 0.U

  rob.done := false.B

  // regfile unit
  rfu_w.valid := false.B
  rfu_w.bits.addr := head.rd.idx
  rfu_w.bits.data := head.rd.value

  // register alias table
  rat.valid := false.B
  rat.bits.addr := head.rd.idx
  rat.bits.tag := head_tag

  csr.except.valid := false.B
  csr.except.bits.xepc := head.pc
  csr.except.bits.xcause := head.except.mcause
  csr.except.bits.xtval := head.except.mtval

  fence_i := false.B

  val dbg_valid = WireDefault(false.B)
  val dbg_dnpc = WireDefault(head.pc)
  val dbg_is_mmio = WireDefault(false.B)

  // ---- Commit logic ----
  when(head_valid) {
    when(head.except.valid) { // except happen
      csr.except.valid := true.B

      flush := true.B
      redirect.valid := true.B
      redirect.bits.correct_npc := csr.xtvec
      redirect.bits.wrong_pc := head.pc

      rob.done := true.B

      dbg_valid := true.B
      dbg_dnpc := csr.xtvec
      dbg_is_mmio := false.B

    }.elsewhen(head_is_mret) { // mret
      flush := true.B
      redirect.valid := true.B
      redirect.bits.correct_npc := csr.xepc
      redirect.bits.wrong_pc := head.pc

      rob.done := true.B

      dbg_valid := true.B
      dbg_dnpc := csr.xepc
      dbg_is_mmio := false.B

    }.otherwise {
      rfu_w.valid := rob_rd_wen
      rfu_w.bits.data := head.rd.value

      rat.valid := rob_rd_wen

      cdb.valid := rob_rd_wen && (head_is_mem || head_is_csr)
      cdb.bits.value := head.rd.value

      flush := mispredict
      redirect.valid := mispredict
      redirect.bits.correct_npc := actual_npc
      redirect.bits.wrong_pc := head.pc

      rob.done := true.B

      dbg_valid := true.B
      dbg_dnpc := Mux(mispredict, actual_npc, head.pc + 4.U)
      dbg_is_mmio := head_is_mem && head.mem.is_mmio
    }
  }

  // sequential sync: delay 1 cycle
  // for writing the register could be read
  val probe = IO(Valid(new DebugCommitBundle))
  probe.valid := RegNext(dbg_valid)
  probe.bits.pc := RegNext(head.pc)
  probe.bits.dnpc := RegNext(dbg_dnpc)
  probe.bits.inst := RegNext(head.inst)
  probe.bits.is_mmio := RegNext(dbg_is_mmio)
}
