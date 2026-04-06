package ysyx.core.backend

import chisel3._
import chisel3.util._

import ysyx.core.common._
import ysyx.core.frontend._

class RenameStageOutput extends NPCBundle {
  val dec = new DecodeStageOutput
  
  // issuse queue
  val prs1 = UInt(NRPhyRegBits.W)
  val prs2 = UInt(NRPhyRegBits.W)
  val prd = UInt(NRPhyRegBits.W)
  val old_prd = UInt(NRPhyRegBits.W)
  val rd_wen = Bool()

  // dispatch stage could write back to prf
  val disp_rd_val = UInt(dataBits.W)
  val disp_rd_defen = Bool()
  val disp_target_npc = UInt(addrBits.W)
}

class RenameStage extends NPCModule {
  val io = IO(new Bundle {
    val in = Flipped(Decoupled(new DecodeStageOutput))
    val out = Decoupled(new RenameStageOutput)
    val frat = Vec(3, new FutureRATReadPort) // rs1, rs2, rd(old_prd)
    val frat_write = Valid(new FutureRATWritePort)
    val busy_set = Valid(UInt(NRPhyRegBits.W))
    val freelist_alloc = Flipped(Decoupled(UInt(NRPhyRegBits.W)))
  })

  val dec_out = io.in.bits
  val inst_type = dec_out.ctrl.inst_type
  val pc = dec_out.pc
  val imm = dec_out.imm
  val rd_idx = dec_out.rd_idx
  val rs1_idx = dec_out.rs1_idx
  val rs2_idx = dec_out.rs2_idx

  // ============================================================
  // Determine if instruction writes a register
  // ============================================================
  // format: off
  val rd_wen = Seq(InstType.R_ALU, InstType.I_ALU, InstType.R_ALU_W, InstType.I_ALU_W, InstType.JALR, InstType.LOAD,
    InstType.JAL, InstType.LUI, InstType.AUIPC, InstType.CSR)
    .map(inst_type === _).reduce(_ || _) && (rd_idx =/= 0.U)
  // format: on

  // ============================================================
  // FutureRAT lookup: rs1, rs2, rd(for old_prd)
  // ============================================================
  io.frat(0).addr := rs1_idx
  io.frat(1).addr := rs2_idx
  io.frat(2).addr := rd_idx

  val prs1 = io.frat(0).preg
  val prs2 = io.frat(1).preg
  val old_prd = io.frat(2).preg

  // ============================================================
  // FreeList allocation
  // ============================================================
  val new_prd = io.freelist_alloc.bits
  val need_alloc = io.in.valid && io.out.ready && rd_wen
  io.freelist_alloc.ready := need_alloc

  val prd = Mux(rd_wen, new_prd, 0.U)

  // ============================================================
  // FutureRAT write (rd -> new_prd)
  // ============================================================
  io.frat_write.valid := io.in.fire && rd_wen
  io.frat_write.bits.addr := rd_idx
  io.frat_write.bits.preg := new_prd

  // ============================================================
  // BusyTable: set new_prd as busy
  // ============================================================
  io.busy_set.valid := io.in.fire && rd_wen
  io.busy_set.bits := new_prd

  // ============================================================
  // Dispatch-resolved value (jalr, jal, lui, auipc)
  // ============================================================
  // format: off
  val disp_rd_defen = Seq(InstType.JALR, InstType.JAL, InstType.LUI, InstType.AUIPC)
    .map(inst_type === _).reduce(_ || _) && (rd_idx =/= 0.U)
  // format: on
  val disp_rd_val = MuxLookup(inst_type, 0.U)(
    Seq(
      InstType.JALR -> (pc + 4.U),
      InstType.JAL -> (pc + 4.U),
      InstType.LUI -> imm,
      InstType.AUIPC -> (pc + imm)
    )
  )

  // ============================================================
  // Override prs2 for instructions that don't use rs2 from PRF
  // ============================================================
  // format: off
  val use_imm = Seq(InstType.I_ALU, InstType.I_ALU_W, InstType.JALR).map(inst_type === _).reduce(_ || _)
  // format: on
  val is_csr = inst_type === InstType.CSR
  val is_load = inst_type === InstType.LOAD
  val final_prs2 = Mux(use_imm || is_csr || is_load, 0.U, prs2)

  // ============================================================
  // Backpressure: stall if freelist empty and need alloc
  // ============================================================
  val stall = need_alloc && !io.freelist_alloc.valid
  io.in.ready := io.out.ready && !stall
  io.out.valid := io.in.valid && !stall

  // ============================================================
  // Output
  // ============================================================
  val out = io.out.bits
  out.dec := dec_out
  out.prs1 := prs1
  out.prs2 := final_prs2
  out.prd := prd
  out.old_prd := Mux(rd_wen, old_prd, 0.U)
  out.rd_wen := rd_wen
  out.disp_rd_val := disp_rd_val
  out.disp_rd_defen := disp_rd_defen
  out.disp_target_npc := pc + imm
}
