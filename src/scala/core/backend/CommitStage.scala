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
}

class CommitStage extends NPCModule {
  val io = IO(new Bundle {
    // ROB
    val rob = new Bundle {
      val commit = Flipped(Decoupled(new CommitBundle))
      val wb_commit = Valid(new WBCommitBundle)
    }
    // CSR — exception + retire
    val csr = new Bundle {
      val exception = new CSRCommitIO
      val retire = new Bundle {
        val late  = Flipped(new LateExecIO)
        val addr  = Output(UInt(NRCSRbits.W))
        val wop   = Output(CSROpType())
        val wen   = Output(Bool())
        val wdata = Output(UInt(dataBits.W))
      }
      val xepc  = Input(UInt(dataBits.W))
      val xtvec = Input(UInt(dataBits.W))
    }
    // LSU — commit driven
    val lsu = new Bundle {
      val late     = Flipped(new LateExecIO)
      val addr     = Output(UInt(addrBits.W))
      val size     = Output(UInt(2.W))
      val sign_ext = Output(Bool())
      val r_en     = Output(Bool())
      val w_en     = Output(Bool())
      val wdata    = Output(UInt(dataBits.W))
      val is_mmio  = Output(Bool())
    }
    // RFU writeback
    val rfu = new Bundle {
      val wen   = Output(Bool())
      val rd_i  = Output(UInt(NRRegbits.W))
      val wdata = Output(UInt(dataBits.W))
    }
    // RAT commit
    val rat_commit = Flipped(new RATCommitPort)
    // IFU redirect
    val ifu = new Bundle {
      val flush    = Output(Bool())
      val redirect = Output(new RedirectBundle)
    }
    // CDB2 broadcast
    val cdb2 = Output(new CDBBundle)
    // fence_i
    val fence_i = Output(Bool())
    // Debug
    val debug = Output(new DebugCommitBundle)
  })

  // ---- Head entry aliases ----
  val head = io.rob.commit.bits.entry
  val head_tag = io.rob.commit.bits.tag
  val head_valid = io.rob.commit.valid

  val head_is_mem = head.mem.r_en || head.mem.w_en
  val head_is_csr = head.csr_wen

  // ---- State machine ----
  object CommitState extends ChiselEnum {
    val idle, late_wait = Value
  }
  val commitStateQ = RegInit(CommitState.idle)

  // ---- Defaults ----
  val flush = WireDefault(false.B)
  val redirect = Wire(new RedirectBundle)
  redirect.valid := false.B
  redirect.target := 0.U

  io.ifu.flush := flush
  io.ifu.redirect := redirect

  // CDB2
  io.cdb2.valid := false.B
  io.cdb2.tag := head_tag
  io.cdb2.value := 0.U

  // ROB wb_commit
  io.rob.wb_commit.valid := false.B
  io.rob.wb_commit.bits.tag := head_tag
  io.rob.wb_commit.bits.value := 0.U

  io.rob.commit.ready := false.B

  // RFU
  io.rfu.wen := false.B
  io.rfu.rd_i := head.rd_idx
  io.rfu.wdata := head.rd_val

  // RAT commit
  io.rat_commit.en := false.B
  io.rat_commit.addr := head.rd_idx
  io.rat_commit.tag := head_tag

  // CSR exception
  io.csr.exception.xepc := head.pc
  io.csr.exception.xepc_wen := false.B
  io.csr.exception.xcause := head.mcause
  io.csr.exception.xcause_wen := false.B
  io.csr.exception.xtval := head.xtval
  io.csr.exception.xtval_wen := false.B

  // CSR retire
  io.csr.retire.late.req := false.B
  io.csr.retire.addr := head.imm(NRCSRbits - 1, 0)
  io.csr.retire.wop := head.csr_op
  io.csr.retire.wen := false.B
  io.csr.retire.wdata := head.alu_result

  // LSU
  io.lsu.late.req := false.B
  io.lsu.addr := head.mem.addr
  io.lsu.size := head.mem.size
  io.lsu.sign_ext := head.mem.sign_ext
  io.lsu.r_en := head.mem.r_en
  io.lsu.w_en := head.mem.w_en
  io.lsu.wdata := head.mem.wdata
  io.lsu.is_mmio := head.mem.is_mmio

  // fence_i
  io.fence_i := false.B

  // Debug
  val dbg_valid = RegInit(false.B)
  val dbg_pc = Reg(UInt(dataBits.W))
  val dbg_dnpc = Reg(UInt(dataBits.W))
  val dbg_inst = Reg(UInt(instBits.W))
  dbg_valid := false.B

  // ---- Commit state machine ----
  switch(commitStateQ) {
    is(CommitState.idle) {
      when(head_valid) {
        when(head.except_en) {
          io.csr.exception.xepc_wen := true.B
          io.csr.exception.xcause_wen := true.B
          io.csr.exception.xtval_wen := true.B

          flush := true.B
          redirect.valid := true.B
          redirect.target := io.csr.xtvec

          io.rob.commit.ready := true.B

          dbg_valid := true.B
          dbg_pc := head.pc
          dbg_dnpc := io.csr.xtvec
          dbg_inst := head.inst

        }.elsewhen(head_is_mem) {
          io.lsu.late.req := true.B
          when(io.lsu.late.done) {
            when(head.rd_def) {
              io.rfu.wen := true.B
              io.rfu.wdata := io.lsu.late.result
            }
            io.rob.wb_commit.valid := head.rd_def
            io.rob.wb_commit.bits.value := io.lsu.late.result
            io.cdb2.valid := head.rd_def
            io.cdb2.value := io.lsu.late.result

            io.rat_commit.en := head.rd_def
            io.rob.commit.ready := true.B

            dbg_valid := true.B
            dbg_pc := head.pc
            dbg_dnpc := head.pc + 4.U
            dbg_inst := head.inst
          }.otherwise {
            commitStateQ := CommitState.late_wait
          }

        }.elsewhen(head_is_csr) {
          io.csr.retire.late.req := true.B
          io.csr.retire.wen := head.csr_wen
          val csr_rd = io.csr.retire.late.result

          when(head.rd_def) {
            io.rfu.wen := true.B
            io.rfu.wdata := csr_rd
          }

          io.rob.wb_commit.valid := head.rd_def
          io.rob.wb_commit.bits.value := csr_rd
          io.cdb2.valid := head.rd_def
          io.cdb2.value := csr_rd

          io.rat_commit.en := head.rd_def

          io.rob.commit.ready := true.B

          dbg_valid := true.B
          dbg_pc := head.pc
          dbg_dnpc := head.pc + 4.U
          dbg_inst := head.inst

        }.elsewhen(head.is_mret) {
          flush := true.B
          redirect.valid := true.B
          redirect.target := io.csr.xepc

          io.rob.commit.ready := true.B

          dbg_valid := true.B
          dbg_pc := head.pc
          dbg_dnpc := io.csr.xepc
          dbg_inst := head.inst

        }.otherwise {
          when(head.rd_def) {
            io.rfu.wen := true.B
            io.rfu.wdata := head.rd_val
          }

          io.rat_commit.en := head.rd_def

          when(head.mispredict) {
            flush := true.B
            redirect.valid := true.B
            redirect.target := head.target_npc
          }

          io.rob.commit.ready := true.B

          dbg_valid := true.B
          dbg_pc := head.pc
          dbg_dnpc := Mux(
            head.mispredict,
            head.target_npc,
            head.pc + 4.U
          )
          dbg_inst := head.inst
        }
      }
    }

    is(CommitState.late_wait) {
      io.lsu.late.req := true.B
      when(io.lsu.late.done) {
        when(head.rd_def) {
          io.rfu.wen := true.B
          io.rfu.wdata := io.lsu.late.result
        }

        io.rob.wb_commit.valid := head.rd_def
        io.rob.wb_commit.bits.value := io.lsu.late.result
        io.cdb2.valid := head.rd_def
        io.cdb2.value := io.lsu.late.result

        io.rat_commit.en := head.rd_def
        io.rob.commit.ready := true.B
        commitStateQ := CommitState.idle

        dbg_valid := true.B
        dbg_pc := head.pc
        dbg_dnpc := head.pc + 4.U
        dbg_inst := head.inst
      }
    }
  }

  io.debug.valid := dbg_valid
  io.debug.pc := dbg_pc
  io.debug.dnpc := dbg_dnpc
  io.debug.inst := dbg_inst
}
