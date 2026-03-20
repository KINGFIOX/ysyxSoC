package ysyx.core.backend

import chisel3._
import chisel3.util._

import ysyx.core.common._
import ysyx.core.frontend._

class RenameStageOutput extends NPCBundle {
  val dec = new DecodeStageOutput
  val src = Vec(2, new IQSrcBundle)
  val disp_rd_val = UInt(dataBits.W)
  val disp_rd_defen = Bool()
  val disp_target_npc = UInt(addrBits.W)
}

class RenameStage extends NPCModule {
  val io = IO(new Bundle {
    val in = Flipped(Decoupled(new DecodeStageOutput))
    val out = Decoupled(new RenameStageOutput)
    val rat = Vec(2, new RATReadPort)
    val rob = Vec(2, new RobReadBundle)
    val disp_fwd = Flipped(Valid(new DispFwdBundle))
    val rfu_query = Vec(2, new RFUReadPort)
    val cdb = Vec(2, Flipped(Valid(new CDBBundle)))
  })

  // backpress
  io.in.ready := io.out.ready
  io.out.valid := io.in.valid

  val out = io.out.bits
  val dec_out = io.in.bits
  out.dec := dec_out

  val inst_type = dec_out.ctrl.inst_type
  val pc = dec_out.pc
  val imm = dec_out.imm
  val rd_idx = dec_out.rd_idx
  val rs_idx = VecInit(dec_out.rs1_idx, dec_out.rs2_idx)

  val disp_fwd = io.disp_fwd

  // ============================================================
  // Dispatch-resolved value (jalr, jal, lui, auipc)
  // ============================================================
  // format: off
  out.disp_rd_defen := Seq( InstType.JALR, InstType.JAL, InstType.LUI, InstType.AUIPC
    ).map(inst_type === _).reduce(_ || _) && (rd_idx =/= 0.U)
  // format: on
  out.disp_rd_val := MuxLookup(inst_type, 0.U)(
    Seq(
      InstType.JALR -> (pc + 4.U),
      InstType.JAL -> (pc + 4.U),
      InstType.LUI -> imm,
      InstType.AUIPC -> (pc + imm)
    )
  )
  out.disp_target_npc := pc + imm

  // ============================================================
  // RAT lookup + RFU read
  // ============================================================
  for (i <- 0 until 2) {
    io.rat(i).addr := rs_idx(i)
    io.rfu_query(i).addr := rs_idx(i)
  }

  // ============================================================
  // Source operand resolution (symmetric for RS1 / RS2)
  // ============================================================
  for (i <- 0 until 2) {
    // Dispatcher tag forward: override RAT when the in-flight dispatch
    // is defining the same architectural register.
    val disp_val_hit = disp_fwd.valid && (disp_fwd.bits.rd_idx === rs_idx(i))
    assert(
      // rf_defen -> (rd_idx =/= 0.U)
      !disp_fwd.valid || (rd_idx =/= 0.U),
      "(RenameStage) x0 should not be forwarded"
    )

    val tag = io.rat(i).tag
    val busy = io.rat(i).busy // x0, always free

    io.rob(i).tag := tag

    val cdb0 = io.cdb(0)
    val cdb1 = io.cdb(1)

    val free = !busy
    val cdb0_hit = cdb0.valid && (cdb0.bits.tag === tag)
    val cdb1_hit = cdb1.valid && (cdb1.bits.tag === tag)
    val rob_hit = io.rob(i).valid

    out.src(i).ready := free || disp_val_hit || cdb0_hit || cdb1_hit || rob_hit
    out.src(i).tag := tag
    out.src(i).value := MuxCase(
      0.U,
      Seq(
        free -> io.rfu_query(i).data, // x0 -> 0
        disp_val_hit -> disp_fwd.bits.value,
        cdb0_hit -> cdb0.bits.value,
        cdb1_hit -> cdb1.bits.value,
        rob_hit -> io.rob(i).value
      )
    )
  }

  // ============================================================
  // Immediate-to-operand: override src(1) for imm-using instructions
  // ============================================================
  // format: off
  val use_imm = Seq(InstType.I_ALU, InstType.JALR).map(inst_type === _).reduce(_ || _)
  // format: on
  val is_csr = inst_type === InstType.CSR
  val is_load = inst_type === InstType.LOAD

  when(use_imm) {
    out.src(1).value := imm
    out.src(1).tag := 0.U
    out.src(1).ready := true.B
  }.elsewhen(is_csr) {
    out.src(1).value := 0.U
    out.src(1).tag := 0.U
    out.src(1).ready := true.B
  }.elsewhen(is_load) {
    out.src(1).ready := true.B
  }
}
