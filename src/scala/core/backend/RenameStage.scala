package ysyx.core.backend

import chisel3._
import chisel3.util._

import ysyx.core.common._
import ysyx.core.frontend._

// FIXME: data hazard between Dispatcher and RenameStage
// the instruction in Dispatcher alloc the rob entry and define the rd.
// However, the instruction in RenameStage retrive the obsolete value.
class RenameStageOutput extends NPCBundle {
  val dec = new DecodeStageOutput
  val src1 = new IQSrcBundle
  val src2 = new IQSrcBundle
  val disp_rd_val = UInt(dataBits.W)
  val disp_rd_val_valid = Bool()
  val disp_mispredict = Bool()
  val disp_target_npc = UInt(addrBits.W)
  val dispatch_mcause = UInt(dataBits.W)
  val dispatch_mtval = UInt(dataBits.W)
}

class RenameStage extends NPCModule {
  val io = IO(new Bundle {
    val in = Flipped(Decoupled(new DecodeStageOutput))
    val out = Decoupled(new RenameStageOutput)
    val rat = new Bundle {
      val rs1 = Flipped(new RATReadPort)
      val rs2 = Flipped(new RATReadPort)
    }
    val rob = new Bundle {
      val fwd1 = Flipped(new RobFwdBundle)
      val fwd2 = Flipped(new RobFwdBundle)
    }
    val disp_fwd = Flipped(new DispFwdBundle)
    val rfu = new Bundle {
      val rs1_i = Output(UInt(NRRegbits.W))
      val rs1_v = Input(UInt(dataBits.W))
      val rs2_i = Output(UInt(NRRegbits.W))
      val rs2_v = Input(UInt(dataBits.W))
    }
    val cdb1 = Input(new CDBBundle)
    val cdb2 = Input(new CDBBundle)
  })

  io.in.ready := io.out.ready
  io.out.valid := io.in.valid

  val dec = io.in.bits
  val ctrl = dec.ctrl
  val pc = dec.ifu.pc
  val imm = dec.imm

  io.out.bits.dec := dec

  // ============================================================
  // RAT lookup
  // ============================================================
  io.rat.rs1.addr := dec.rs1_idx
  io.rat.rs2.addr := dec.rs2_idx

  // ============================================================
  // RFU read
  // ============================================================
  io.rfu.rs1_i := dec.rs1_idx
  io.rfu.rs2_i := dec.rs2_idx

  // ============================================================
  // TODO: Dispatcher forward (bypass for RAT-not-yet-written hazard)
  // ============================================================
  // format: off
  val disp_rs1_hit = io.disp_fwd.rd_wen && (io.disp_fwd.rd_idx === dec.rs1_idx) && (dec.rs1_idx =/= 0.U)
  val disp_rs2_hit = io.disp_fwd.rd_wen && (io.disp_fwd.rd_idx === dec.rs2_idx) && (dec.rs2_idx =/= 0.U)
  // format: on

  val rs1_busy = io.rat.rs1.busy || disp_rs1_hit
  val rs1_tag = Mux(disp_rs1_hit, io.disp_fwd.rd_tag, io.rat.rs1.tag)
  val rs2_busy = io.rat.rs2.busy || disp_rs2_hit
  val rs2_tag = Mux(disp_rs2_hit, io.disp_fwd.rd_tag, io.rat.rs2.tag)

  // ============================================================
  // ROB forward query
  // ============================================================
  io.rob.fwd1.tag := rs1_tag
  io.rob.fwd2.tag := rs2_tag

  // ============================================================
  // Source operand resolution (value capture)
  // ============================================================

  // --- RS1 ---
  val rs1_free = !ctrl.needs_rs1 || !rs1_busy // ctrl.needs_rs1 -> !rs1_busy
  val rs1_cdb1 = io.cdb1.valid && (io.cdb1.tag === rs1_tag)
  val rs1_cdb2 = io.cdb2.valid && (io.cdb2.tag === rs1_tag)
  val rs1_rob = io.rob.fwd1.valid

  io.out.bits.src1.value := MuxCase(
    0.U,
    Seq(
      rs1_free -> io.rfu.rs1_v,
      rs1_cdb1 -> io.cdb1.value,
      rs1_cdb2 -> io.cdb2.value,
      rs1_rob -> io.rob.fwd1.value
    )
  )
  io.out.bits.src1.tag := rs1_tag
  io.out.bits.src1.ready := rs1_free || rs1_cdb1 || rs1_cdb2 || rs1_rob

  // --- RS2 ---
  val rs2_free = !ctrl.needs_rs2 || !rs2_busy
  val rs2_cdb1 = io.cdb1.valid && (io.cdb1.tag === rs2_tag)
  val rs2_cdb2 = io.cdb2.valid && (io.cdb2.tag === rs2_tag)
  val rs2_rob = io.rob.fwd2.valid

  io.out.bits.src2.value := MuxCase(
    0.U,
    Seq(
      rs2_free -> io.rfu.rs2_v,
      rs2_cdb1 -> io.cdb1.value,
      rs2_cdb2 -> io.cdb2.value,
      rs2_rob -> io.rob.fwd2.value
    )
  )
  io.out.bits.src2.tag := rs2_tag
  io.out.bits.src2.ready := rs2_free || rs2_cdb1 || rs2_cdb2 || rs2_rob

  // ============================================================
  // Dispatch-time resolved values
  // ============================================================
  io.out.bits.disp_rd_val := MuxCase(
    0.U,
    Seq(
      ctrl.is_lui -> imm,
      ctrl.is_auipc -> (pc + imm),
      ctrl.is_jal -> (pc + 4.U),
      ctrl.is_jalr -> (pc + 4.U)
    )
  )
  // format: off
  io.out.bits.disp_rd_val_valid := ctrl.is_lui || ctrl.is_auipc || ctrl.is_jal || ctrl.is_jalr
  // format: on
  io.out.bits.disp_mispredict := ctrl.is_jal
  io.out.bits.disp_target_npc := pc + imm

  // ============================================================
  // Exception mcause / mtval conversion
  // ============================================================
  val ifu_mcause = MuxCase(
    0.U,
    Seq(
      (dec.ifu.exception === IFUExceptionType.ifu_INSTRUCTION_ADDRESS_MISALIGNED) -> 0.U,
      (dec.ifu.exception === IFUExceptionType.ifu_INSTRUCTION_ACCESS_FAULT) -> 1.U,
      (dec.ifu.exception === IFUExceptionType.ifu_INSTRUCTION_PAGE_FAULT) -> 12.U
    )
  )
  val cu_mcause = MuxCase(
    0.U,
    Seq(
      (ctrl.except_type === CUExceptionType.cu_ILLEGAL_INSTRUCTION) -> 2.U,
      (ctrl.except_type === CUExceptionType.cu_BREAKPOINT) -> 3.U,
      (ctrl.except_type === CUExceptionType.cu_ECALL_FROM_U_MODE) -> 8.U,
      (ctrl.except_type === CUExceptionType.cu_ECALL_FROM_S_MODE) -> 9.U,
      (ctrl.except_type === CUExceptionType.cu_ECALL_FROM_M_MODE) -> 11.U
    )
  )
  io.out.bits.dispatch_mcause := Mux(
    dec.ifu.exceptionEn,
    ifu_mcause,
    cu_mcause
  )
  io.out.bits.dispatch_mtval := Mux(
    dec.ifu.exceptionEn,
    dec.ifu.mtval,
    ctrl.mtval
  )
}
