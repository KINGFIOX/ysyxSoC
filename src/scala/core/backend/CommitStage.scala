package ysyx.core.backend

import chisel3._
import chisel3.util._

import ysyx.core.common._
import ysyx.core.frontend._

class PerfBundle extends NPCBundle {
  val commit_cnt = UInt(dataBits.W)
  val branch_cnt = UInt(dataBits.W)
  val branch_mispredict_cnt = UInt(dataBits.W)
  val flush_cnt = UInt(dataBits.W)
}

class DebugCommitBundle extends NPCBundle {
  val pc = UInt(dataBits.W)
  val dnpc = UInt(dataBits.W)
  val inst = UInt(instBits.W)
  val is_mmio = Bool()
}

class CommitStage extends NPCModule {
  // ArchRAT write
  val arch_rat_w = IO(Valid(new ArchRATWritePort))
  // FreeList: release old_prd (also serves as commit_alloc)
  val freelist_free = IO(Valid(UInt(NRPhyRegBits.W)))

  val csr = IO(new Bundle {
    val except = Valid(new CsrExceptWritePort)
    val xepc = Input(UInt(dataBits.W))
    val xtvec = Input(UInt(dataBits.W))
  })
  val rob = IO(Flipped(ReqDone(new CommitBundle)))
  val redirect = IO(Valid(new RedirectBundle))
  val flush = IO(Bool())
  val fence_i = IO(Bool())

  // ---- Head entry aliases ----
  val head_entry = rob.bits.entry
  val head_tag = rob.bits.tag
  val head_valid = rob.req

  val head_is_mem = head_entry.mem.r_en || head_entry.mem.w_en
  val head_is_csr = head_entry.inst_type === InstType.CSR
  val head_is_mret = head_entry.inst_type === InstType.MRET

  // ---- Defaults ----
  rob.done := head_valid

  // ArchRAT
  arch_rat_w.valid := false.B
  arch_rat_w.bits.addr := head_entry.rd.arch_rd
  arch_rat_w.bits.preg := head_entry.rd.new_prd

  // FreeList
  freelist_free.valid := false.B
  freelist_free.bits := head_entry.rd.old_prd

  csr.except.valid := false.B
  csr.except.bits.xepc := head_entry.pc
  csr.except.bits.xcause := head_entry.except.mcause
  csr.except.bits.xtval := head_entry.except.mtval

  fence_i := false.B

  val dbg_is_mmio = WireDefault(false.B)

  redirect.valid := rob.fire
  redirect.bits.wrong_pc := head_entry.pc
  redirect.bits.inst_type := head_entry.inst_type
  redirect.bits.jal := head_entry.jal
  redirect.bits.jalr := head_entry.jalr
  redirect.bits.bru := head_entry.bru
  redirect.bits.ghr := head_entry.ghr
  redirect.bits.dnpc := MuxCase(
    head_entry.predict_npc,
    Seq(
      (head_entry.inst_type === InstType.JAL) -> head_entry.jal.dnpc,
      (head_entry.inst_type === InstType.JALR) -> head_entry.jalr.dnpc,
      (head_entry.inst_type === InstType.BRANCH) -> Mux(head_entry.bru.br_flag, head_entry.bru.dnpc, head_entry.bru.snpc),
      (head_entry.inst_type === InstType.MRET) -> csr.xepc,
      (head_entry.except.valid) -> csr.xtvec
    )
  )
  val is_diff = (redirect.bits.dnpc =/= head_entry.predict_npc)
  redirect.bits.mispredict := false.B
  flush := rob.fire && is_diff

  // ---- Commit logic ----
  when(head_valid) {
    when(head_entry.except.valid) {
      csr.except.valid := true.B
    }.elsewhen(head_is_mret) {
      // mret: no register write, no PRF change
    }.otherwise {
      // Write ArchRAT and free old_prd
      when(head_entry.rd.rd_wen) {
        arch_rat_w.valid := true.B
        freelist_free.valid := true.B
      }

      dbg_is_mmio := head_is_mem && head_entry.is_mmio
      redirect.bits.mispredict := is_diff
    }
  }

  // sequential sync: delay 1 cycle
  val probe = IO(Valid(new DebugCommitBundle))
  probe.valid := RegNext(rob.fire)
  probe.bits.pc := RegNext(head_entry.pc)
  probe.bits.dnpc := RegNext(redirect.bits.dnpc)
  probe.bits.inst := RegNext(head_entry.inst)
  probe.bits.is_mmio := RegNext(dbg_is_mmio)

  // ---- Perf counters ----
  val perf_commit_cnt = RegInit(0.U(dataBits.W))
  val perf_branch_cnt = RegInit(0.U(dataBits.W))
  val perf_branch_mispredict_cnt = RegInit(0.U(dataBits.W))
  val perf_flush_cnt = RegInit(0.U(dataBits.W))

  when(rob.fire) {
    perf_commit_cnt := perf_commit_cnt + 1.U
    val is_cf = head_entry.inst_type === InstType.BRANCH ||
                head_entry.inst_type === InstType.JAL ||
                head_entry.inst_type === InstType.JALR
    when(is_cf) {
      perf_branch_cnt := perf_branch_cnt + 1.U
      when(redirect.bits.mispredict) {
        perf_branch_mispredict_cnt := perf_branch_mispredict_cnt + 1.U
      }
    }
    when(flush) {
      perf_flush_cnt := perf_flush_cnt + 1.U
    }
  }

  val perf = IO(Output(new PerfBundle))
  perf.commit_cnt := perf_commit_cnt
  perf.branch_cnt := perf_branch_cnt
  perf.branch_mispredict_cnt := perf_branch_mispredict_cnt
  perf.flush_cnt := perf_flush_cnt
}
