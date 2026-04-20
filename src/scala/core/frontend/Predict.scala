package ysyx.core.frontend

import chisel3._
import chisel3.util._
import ysyx.core.common._
import ysyx.core.backend.InstType
import ysyx.core.backend.CU
import ysyx.core.backend.IGU

class Predict extends NPCModule {

  val io = IO(new Bundle {
    val predict = new PredictBundle
    val redirect = Input(Valid(new RedirectBundle))
  })

  // ============================================================
  // Sub-modules
  // ============================================================
  val cu_ = Module(new CU)
  val igu_ = Module(new IGU)
  val bht_ = Module(new BHT)
  val ijtc_ = Module(new IJTC)
  val ras_ = Module(new RAS)

  // ============================================================
  // Mini-decode via CU + IGU
  // ============================================================
  cu_.io.in.inst := io.predict.inst
  cu_.io.in.pc := io.predict.pc
  igu_.io.in.inst_31_7 := io.predict.inst(31, 7)
  igu_.io.in.imm_type := cu_.io.out.imm_type

  val inst_type = cu_.io.out.inst_type
  val imm = igu_.io.out.imm
  val dnpc = io.predict.pc + imm
  val snpc = io.predict.pc + 4.U

  val rd = io.predict.inst(11, 7)
  val rs1 = io.predict.inst(19, 15)
  val is_call = (inst_type === InstType.JAL || inst_type === InstType.JALR) && (rd === 1.U || rd === 5.U)
  val is_ret = (inst_type === InstType.JALR) && (rd === 0.U) && (rs1 === 1.U)

  // ============================================================
  // BHT lookup (branch direction)
  // ============================================================
  bht_.io.lookup.pc := io.predict.pc

  val bht_taken = Mux(bht_.io.lookup.hit, bht_.io.lookup.br_flag, imm(31))

  // ============================================================
  // IJTC lookup (indirect jump target)
  // ============================================================
  ijtc_.io.lookup.pc := io.predict.pc

  // ============================================================
  // RAS (front-end only push/pop).  Gated by `io.predict.commit` so the
  // stack is only updated when F3 actually hands the instruction off.
  // Otherwise a multi-cycle F3 stall or a wrong-path F3 (about to be
  // squashed by fe_redirect / back-end flush) would push/pop multiple
  // times per real call/ret.
  // ============================================================
  ras_.io.push.valid := is_call && io.predict.commit
  ras_.io.push.bits.dnpc := snpc
  ras_.io.pop.valid := is_ret && io.predict.commit

  // ============================================================
  // Prediction mux
  // ============================================================
  io.predict.dnpc := MuxCase(
    snpc,
    Seq(
      (inst_type === InstType.JAL) -> dnpc,
      (inst_type === InstType.BRANCH) -> Mux(bht_taken, dnpc, snpc),
      (inst_type === InstType.JALR && is_ret) -> ras_.io.pop.bits.dnpc,
      (inst_type === InstType.JALR && ijtc_.io.lookup.hit) -> ijtc_.io.lookup.dnpc
    )
  )

  io.predict.ghr := bht_.io.lookup.ghr

  // ============================================================
  // Backend update (from redirect / commit)
  // ============================================================
  val r = io.redirect.bits

  // BHT update: on BRANCH commit
  bht_.io.update.valid := io.redirect.valid && (r.inst_type === InstType.BRANCH)
  bht_.io.update.bits.pc := r.wrong_pc
  bht_.io.update.bits.br_flag := r.bru.br_flag
  bht_.io.update.bits.ghr := r.ghr

  // IJTC update: on JALR commit (excluding ret)
  ijtc_.io.update.valid := io.redirect.valid && (r.inst_type === InstType.JALR) && !r.jalr.is_ret
  ijtc_.io.update.bits.pc := r.wrong_pc
  ijtc_.io.update.bits.dnpc := r.jalr.dnpc

}
