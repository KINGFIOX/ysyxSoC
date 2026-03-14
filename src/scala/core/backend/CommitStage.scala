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
  val io = IO(new Bundle {
    val rob = new Bundle {
      val commit = Flipped(Decoupled(new CommitBundle))
      val wb_commit = Valid(new WBCommitBundle)
    }
    val csr = new Bundle {
      val exception = new CSRCommitIO
      val retire = new Bundle {
        val late = Flipped(new LateExecIO)
        val addr = Output(UInt(NRCSRbits.W))
        val wop = Output(CSROpType())
        val wen = Output(Bool())
        val wdata = Output(UInt(dataBits.W))
      }
      val xepc = Input(UInt(dataBits.W))
      val xtvec = Input(UInt(dataBits.W))
    }
    val lsu = new Bundle {
      val late = Flipped(new LateExecIO)
      val addr = Output(UInt(addrBits.W))
      val size = Output(UInt(2.W))
      val sign_ext = Output(Bool())
      val r_en = Output(Bool())
      val w_en = Output(Bool())
      val wdata = Output(UInt(dataBits.W))
      val is_mmio = Output(Bool())
    }
    val rfu = new Bundle {
      val wen = Output(Bool())
      val rd_i = Output(UInt(NRRegbits.W))
      val wdata = Output(UInt(dataBits.W))
    }
    val rat_commit = Flipped(new RATCommitPort)
    val ifu = new Bundle {
      val flush = Output(Bool())
      val redirect = Output(new RedirectBundle)
    }
    val cdb2 = Output(new CDBBundle)
    val fence_i = Output(Bool())
    val debug = Output(new DebugCommitBundle)
  })

  // ---- Head entry aliases ----
  val head = io.rob.commit.bits.entry
  val head_tag = io.rob.commit.bits.tag
  val head_valid = io.rob.commit.valid

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

  io.ifu.flush := flush
  io.ifu.redirect := redirect

  io.cdb2.valid := false.B
  io.cdb2.tag := head_tag
  io.cdb2.value := 0.U

  io.rob.wb_commit.valid := false.B
  io.rob.wb_commit.bits.tag := head_tag
  io.rob.wb_commit.bits.value := 0.U

  io.rob.commit.ready := false.B

  io.rfu.wen := false.B
  io.rfu.rd_i := head.rd.idx
  io.rfu.wdata := head.rd.value

  io.rat_commit.en := false.B
  io.rat_commit.addr := head.rd.idx
  io.rat_commit.tag := head_tag

  io.csr.exception.xepc := head.pc
  io.csr.exception.xepc_wen := false.B
  io.csr.exception.xcause := head.except.mcause
  io.csr.exception.xcause_wen := false.B
  io.csr.exception.xtval := head.except.mtval
  io.csr.exception.xtval_wen := false.B

  io.csr.retire.late.req := false.B
  io.csr.retire.addr := head.csr.addr
  io.csr.retire.wop := head.csr.op
  io.csr.retire.wen := false.B
  io.csr.retire.wdata := head.csr.wdata

  io.lsu.late.req := false.B
  io.lsu.addr := head.mem.addr
  io.lsu.size := head.mem.size
  io.lsu.sign_ext := head.mem.sign_ext
  io.lsu.r_en := head.mem.r_en
  io.lsu.w_en := head.mem.w_en
  io.lsu.wdata := head.mem.wdata
  io.lsu.is_mmio := head.mem.is_mmio

  io.fence_i := false.B

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
          io.csr.exception.xepc_wen := true.B
          io.csr.exception.xcause_wen := true.B
          io.csr.exception.xtval_wen := true.B

          flush := true.B
          redirect.valid := true.B
          redirect.correct_npc := io.csr.xtvec
          redirect.wrong_pc := head.pc

          io.rob.commit.ready := true.B

          dbg_valid := true.B
          dbg_pc := head.pc
          dbg_dnpc := io.csr.xtvec
          dbg_inst := head.inst
          dbg_is_mmio := false.B

        }.elsewhen(head_is_mem) {
          io.lsu.late.req := true.B
          when(io.lsu.late.done) {
            when(rd_def) {
              io.rfu.wen := true.B
              io.rfu.wdata := io.lsu.late.result
            }
            io.rob.wb_commit.valid := rd_def
            io.rob.wb_commit.bits.value := io.lsu.late.result
            io.cdb2.valid := rd_def
            io.cdb2.value := io.lsu.late.result

            io.rat_commit.en := rd_def
            io.rob.commit.ready := true.B

            dbg_valid := true.B
            dbg_pc := head.pc
            dbg_dnpc := head.pc + 4.U
            dbg_inst := head.inst
            dbg_is_mmio := head.mem.is_mmio
          }.otherwise {
            commitStateQ := CommitState.late_wait
          }

        }.elsewhen(head_is_csr) {
          io.csr.retire.late.req := true.B
          io.csr.retire.wen := true.B
          val csr_rd = io.csr.retire.late.result

          when(rd_def) {
            io.rfu.wen := true.B
            io.rfu.wdata := csr_rd
          }

          io.rob.wb_commit.valid := rd_def
          io.rob.wb_commit.bits.value := csr_rd
          io.cdb2.valid := rd_def
          io.cdb2.value := csr_rd

          io.rat_commit.en := rd_def

          io.rob.commit.ready := true.B

          dbg_valid := true.B
          dbg_pc := head.pc
          dbg_dnpc := head.pc + 4.U
          dbg_inst := head.inst
          dbg_is_mmio := false.B

        }.elsewhen(head_is_mret) {
          flush := true.B
          redirect.valid := true.B
          redirect.correct_npc := io.csr.xepc
          redirect.wrong_pc := head.pc

          io.rob.commit.ready := true.B

          dbg_valid := true.B
          dbg_pc := head.pc
          dbg_dnpc := io.csr.xepc
          dbg_inst := head.inst
          dbg_is_mmio := false.B

        }.otherwise {
          when(rd_def) {
            io.rfu.wen := true.B
            io.rfu.wdata := head.rd.value
          }

          io.rat_commit.en := rd_def

          when(mispredict) {
            flush := true.B
            redirect.valid := true.B
            redirect.correct_npc := head.target_npc
            redirect.wrong_pc := head.pc
          }

          io.rob.commit.ready := true.B

          dbg_valid := true.B
          dbg_pc := head.pc
          dbg_dnpc := Mux(mispredict, head.target_npc, head.pc + 4.U)
          dbg_inst := head.inst
          dbg_is_mmio := false.B
        }
      }
    }

    is(CommitState.late_wait) {
      io.lsu.late.req := true.B
      when(io.lsu.late.done) {
        when(rd_def) {
          io.rfu.wen := true.B
          io.rfu.wdata := io.lsu.late.result
        }

        io.rob.wb_commit.valid := rd_def
        io.rob.wb_commit.bits.value := io.lsu.late.result
        io.cdb2.valid := rd_def
        io.cdb2.value := io.lsu.late.result

        io.rat_commit.en := rd_def
        io.rob.commit.ready := true.B
        commitStateQ := CommitState.idle

        dbg_valid := true.B
        dbg_pc := head.pc
        dbg_dnpc := head.pc + 4.U
        dbg_inst := head.inst
        dbg_is_mmio := head.mem.is_mmio
      }
    }
  }

  io.debug.valid := dbg_valid
  io.debug.pc := dbg_pc
  io.debug.dnpc := dbg_dnpc
  io.debug.inst := dbg_inst
  io.debug.is_mmio := dbg_is_mmio
}
