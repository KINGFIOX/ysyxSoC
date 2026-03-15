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
}

class CommitStage extends NPCModule {
  val rfu_w = IO(new RFUWritePort)
  val rat_commit = IO(new RATCommitPort)
  val csr = IO(new Bundle {
    val except = new CsrExceptWritePort
    val xepc = Input(UInt(dataBits.W))
    val xtvec = Input(UInt(dataBits.W))
  })
  val probe = IO(new DebugCommitBundle)
  val rob = IO(new Bundle {
    val commit = Flipped(Decoupled(new CommitBundle))
  })
  val ifu = IO(new Bundle {
    val flush = Bool()
    val redirect = new RedirectBundle
  })
  val cdb = IO(new CDBBundle)
  val fence_i = IO(Bool())

  // ---- Head entry aliases ----
  val head = rob.commit.bits.entry
  val head_tag = rob.commit.bits.tag
  val head_valid = rob.commit.valid

  val rd_def = head.rd.idx =/= 0.U
  val head_is_mem = head.mem.r_en || head.mem.w_en
  val head_is_csr = head.inst_type === InstType.CSR
  val head_is_mret = head.inst_type === InstType.MRET

  // format: off
  val is_control = Seq(InstType.JAL, InstType.JALR, InstType.BRANCH)
    .map(head.inst_type === _)
    .reduce(_ || _)
  // format: on
  val mispredict = is_control && (head.target_npc =/= head.predict_npc)

  // ---- Defaults ----
  val flush = WireDefault(false.B)
  val redirect = Wire(new RedirectBundle)
  redirect.valid := false.B
  redirect.correct_npc := 0.U
  redirect.wrong_pc := 0.U

  ifu.flush := flush
  ifu.redirect := redirect

  cdb.valid := false.B
  cdb.tag := head_tag
  cdb.value := 0.U

  rob.commit.ready := false.B

  rfu_w.en := false.B
  rfu_w.addr := head.rd.idx
  rfu_w.data := head.rd.value

  rat_commit.en := false.B
  rat_commit.addr := head.rd.idx
  rat_commit.tag := head_tag

  csr.except.xepc := head.pc
  csr.except.xepc_wen := false.B
  csr.except.xcause := head.except.mcause
  csr.except.xcause_wen := false.B
  csr.except.xtval := head.except.mtval
  csr.except.xtval_wen := false.B

  fence_i := false.B

  val dbg_valid = RegInit(false.B)
  val dbg_pc = Reg(UInt(dataBits.W))
  val dbg_dnpc = Reg(UInt(dataBits.W))
  val dbg_inst = Reg(UInt(instBits.W))
  val dbg_is_mmio = Reg(Bool())
  dbg_valid := false.B

  // ---- Commit logic ----
  when(head_valid) {
    when(head.except.valid) {
      csr.except.xepc_wen := true.B
      csr.except.xcause_wen := true.B
      csr.except.xtval_wen := true.B

      flush := true.B
      redirect.valid := true.B
      redirect.correct_npc := csr.xtvec
      redirect.wrong_pc := head.pc

      rob.commit.ready := true.B

      dbg_valid := true.B
      dbg_pc := head.pc
      dbg_dnpc := csr.xtvec
      dbg_inst := head.inst
      dbg_is_mmio := false.B

    }.elsewhen(head_is_mret) {
      flush := true.B
      redirect.valid := true.B
      redirect.correct_npc := csr.xepc
      redirect.wrong_pc := head.pc

      rob.commit.ready := true.B

      dbg_valid := true.B
      dbg_pc := head.pc
      dbg_dnpc := csr.xepc
      dbg_inst := head.inst
      dbg_is_mmio := false.B

    }.otherwise {
      when(rd_def) {
        rfu_w.en := true.B
        rfu_w.data := head.rd.value
      }

      rat_commit.en := rd_def

      cdb.valid := rd_def && (head_is_mem || head_is_csr)
      cdb.value := head.rd.value

      when(mispredict) {
        flush := true.B
        redirect.valid := true.B
        redirect.correct_npc := head.target_npc
        redirect.wrong_pc := head.pc
      }

      rob.commit.ready := true.B

      dbg_valid := true.B
      dbg_pc := head.pc
      dbg_dnpc := Mux(mispredict, head.target_npc, head.pc + 4.U)
      dbg_inst := head.inst
      dbg_is_mmio := head_is_mem && head.mem.is_mmio
    }
  }

  probe.valid := dbg_valid
  probe.pc := dbg_pc
  probe.dnpc := dbg_dnpc
  probe.inst := dbg_inst
  probe.is_mmio := dbg_is_mmio
}
