package ysyx.core.frontend

import chisel3._
import chisel3.util._
import ysyx.core.common._
import ysyx.core.backend.InstType

class Predict extends NPCModule {

  private val nBTBEntries = 64
  private val ghrBits = 8
  private val nPHTEntries = 1 << ghrBits
  private val btbIdxBits = log2Ceil(nBTBEntries)
  private val btbTagBits = addrBits - btbIdxBits - 2

  val io = IO(new Bundle {
    val predict = new PredictBundle
    val update = Input(Valid(new RedirectBundle))
  })

  // BTB (direct-mapped)
  val btb_valid = RegInit(VecInit(Seq.fill(nBTBEntries)(false.B)))
  val btb_tag = Reg(Vec(nBTBEntries, UInt(btbTagBits.W)))
  val btb_target = Reg(Vec(nBTBEntries, UInt(addrBits.W)))
  val btb_is_branch = Reg(Vec(nBTBEntries, Bool()))

  // GHR (updated at commit time only, no speculative update)
  val ghr = RegInit(0.U(ghrBits.W))

  // PHT (2-bit saturating counters, init to weakly not-taken)
  val pht = RegInit(VecInit(Seq.fill(nPHTEntries)(1.U(2.W))))

  // ---- Prediction ----
  val pc = io.predict.pc
  val btb_idx = pc(btbIdxBits + 1, 2)
  val btb_tag_val = pc(addrBits - 1, btbIdxBits + 2)
  val btb_hit = btb_valid(btb_idx) && (btb_tag(btb_idx) === btb_tag_val)

  val pht_idx = ghr ^ pc(ghrBits + 1, 2)
  val pht_taken = pht(pht_idx)(1) // MSB of 2-bit counter

  val predict_taken = btb_hit && Mux(btb_is_branch(btb_idx), pht_taken, true.B)
  io.predict.dnpc := Mux(predict_taken, btb_target(btb_idx), pc + 4.U)

  // ---- Update (from commit) ----
  when(io.update.valid) {
    val u = io.update.bits
    val is_branch = u.inst_type === InstType.BRANCH
    val is_jal = u.inst_type === InstType.JAL
    val is_jalr = u.inst_type === InstType.JALR
    val taken = u.snpc =/= (u.wrong_pc + 4.U)

    // BTB: write on taken branch / JAL / JALR
    when((is_branch && taken) || is_jal || is_jalr) {
      val u_idx = u.wrong_pc(btbIdxBits + 1, 2)
      btb_valid(u_idx) := true.B
      btb_tag(u_idx) := u.wrong_pc(addrBits - 1, btbIdxBits + 2)
      btb_target(u_idx) := u.snpc
      btb_is_branch(u_idx) := is_branch
    }

    // GHR & PHT: update only for conditional branches
    when(is_branch) {
      ghr := Cat(ghr(ghrBits - 2, 0), taken)

      val u_pht_idx = ghr ^ u.wrong_pc(ghrBits + 1, 2)
      val old_cnt = pht(u_pht_idx)
      pht(u_pht_idx) := Mux(
        taken,
        Mux(old_cnt === 3.U, 3.U, old_cnt + 1.U),
        Mux(old_cnt === 0.U, 0.U, old_cnt - 1.U)
      )
    }
  }
}
