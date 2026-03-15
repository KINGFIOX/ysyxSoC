package ysyx.core.backend

import chisel3._
import chisel3.util._

import ysyx.core.common._
import ysyx.core.frontend._
import ysyx.core.lsu._

class DebugCommitBundle extends NPCBundle {
  val valid = Bool()
  val pc = UInt(dataBits.W)
  val dnpc = UInt(dataBits.W)
  val inst = UInt(instBits.W)
  val is_mmio = Bool()
}

class CommitStage extends NPCModule {
  val lsu = IO(new Bundle {
    val late = new LateExecIO
    val ctrl = new MemLsuInput
  })
  val rfu_w = IO(new RFUWritePort)
  val rat_commit = IO(new RATCommitPort)
  val csr = IO(new Bundle {
    val except = new CsrExceptWritePort
    val retire = new Bundle {
      val late = new LateExecIO
      val wo = new CsrWriteOnlyPort
    }
    val xepc = Input(UInt(dataBits.W)) // for `mret`
    val xtvec = Input(UInt(dataBits.W)) // for `ecall`
  })
  val probe = IO(new DebugCommitBundle)
  val rob = new Bundle {
    val commit = Flipped(Decoupled(new CommitBundle))
    val wb_commit = Valid(new WBCommitBundle) // for late exec unit
  }
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
    .map(head.inst_type === _).reduce(_ || _)
  // format: on
  val mispredict = is_control && (head.target_npc =/= head.predict_npc)

  // ---- State machine ----
  object CommitState extends ChiselEnum {
    val idle, late_wait = Value
  }
  val commitStateQ = RegInit(CommitState.idle)

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

  rob.wb_commit.valid := false.B
  rob.wb_commit.bits.tag := head_tag
  rob.wb_commit.bits.value := 0.U

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

  csr.retire.late.req := false.B
  csr.retire.wo.addr := head.csr.addr
  csr.retire.wo.op := head.csr.op
  csr.retire.wo.wen := false.B
  csr.retire.wo.wdata := head.csr.wdata

  // defaults
  lsu.late.req := false.B
  lsu.ctrl.addr := head.mem.addr
  lsu.ctrl.size := head.mem.size
  lsu.ctrl.sign_ext := head.mem.sign_ext
  lsu.ctrl.r_en := head.mem.r_en
  lsu.ctrl.w_en := head.mem.w_en
  lsu.ctrl.wdata := head.mem.wdata
  lsu.ctrl.is_mmio := head.mem.is_mmio

  fence_i := false.B

  val dbg_valid = RegInit(false.B)
  val dbg_pc = Reg(UInt(dataBits.W))
  val dbg_dnpc = Reg(UInt(dataBits.W))
  val dbg_inst = Reg(UInt(instBits.W))
  val dbg_is_mmio = Reg(Bool())
  dbg_valid := false.B

  // ---- Commit state machine ----
  switch(commitStateQ) {
    is(CommitState.idle) {
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

        }.elsewhen(head_is_mem) {
          lsu.late.req := true.B
          when(lsu.late.done) {
            when(rd_def) {
              rfu_w.en := true.B
              rfu_w.data := lsu.late.result
            }
            rob.wb_commit.valid := rd_def
            rob.wb_commit.bits.value := lsu.late.result
            cdb.valid := rd_def
            cdb.value := lsu.late.result

            rat_commit.en := rd_def
            rob.commit.ready := true.B

            dbg_valid := true.B
            dbg_pc := head.pc
            dbg_dnpc := head.pc + 4.U
            dbg_inst := head.inst
            dbg_is_mmio := head.mem.is_mmio
          }.otherwise {
            commitStateQ := CommitState.late_wait
          }

        }.elsewhen(head_is_csr) {
          csr.retire.late.req := true.B
          csr.retire.wo.wen := true.B
          val csr_rd = csr.retire.late.result

          when(rd_def) {
            rfu_w.en := true.B
            rfu_w.data := csr_rd
          }

          rob.wb_commit.valid := rd_def
          rob.wb_commit.bits.value := csr_rd
          cdb.valid := rd_def
          cdb.value := csr_rd

          rat_commit.en := rd_def

          rob.commit.ready := true.B

          dbg_valid := true.B
          dbg_pc := head.pc
          dbg_dnpc := head.pc + 4.U
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
          dbg_is_mmio := false.B
        }
      }
    }

    is(CommitState.late_wait) {
      lsu.late.req := true.B
      when(lsu.late.done) {
        when(rd_def) {
          rfu_w.en := true.B
          rfu_w.data := lsu.late.result
        }

        rob.wb_commit.valid := rd_def
        rob.wb_commit.bits.value := lsu.late.result
        cdb.valid := rd_def
        cdb.value := lsu.late.result

        rat_commit.en := rd_def
        rob.commit.ready := true.B
        commitStateQ := CommitState.idle

        dbg_valid := true.B
        dbg_pc := head.pc
        dbg_dnpc := head.pc + 4.U
        dbg_inst := head.inst
        dbg_is_mmio := head.mem.is_mmio
      }
    }
  }

  probe.valid := dbg_valid
  probe.pc := dbg_pc
  probe.dnpc := dbg_dnpc
  probe.inst := dbg_inst
  probe.is_mmio := dbg_is_mmio
}
