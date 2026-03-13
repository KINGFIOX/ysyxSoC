package ysyx.core.backend

import chisel3._
import chisel3.util._

import ysyx.core.common._
import ysyx.core.frontend._

class RenameStageOutput extends NPCBundle {
  val pc = UInt(addrBits.W)
  val inst = UInt(InstBits.W)
  val imm = UInt(dataBits.W)
  val rd_idx = UInt(NRRegbits.W)
  val rd_def = Bool()
  val ctrl = new CUOutput
  val go_to_alu = Bool()
  val go_to_bru = Bool()
  val go_to_agu = Bool()
  val dispatch_except_en = Bool()
  val dispatch_resolved = Bool()

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
      val read1 = Flipped(new RATReadPort)
      val read2 = Flipped(new RATReadPort)
    }

    val rfu_rs1_idx = Output(UInt(NRRegbits.W))
    val rfu_rs2_idx = Output(UInt(NRRegbits.W))
    val rfu_rs1_v = Input(UInt(dataBits.W))
    val rfu_rs2_v = Input(UInt(dataBits.W))

    val rob_fwd1 = Flipped(new ForwardBundle)
    val rob_fwd2 = Flipped(new ForwardBundle)

    val cdb1 = Input(new CDBBundle)
    val cdb2 = Input(new CDBBundle)
  })

  io.in.ready := io.out.ready
  io.out.valid := io.in.valid

  val d = io.in.bits
  val o = io.out.bits

  // RAT read
  io.rat.read1.addr := d.rs1_idx
  io.rat.read2.addr := d.rs2_idx

  // RFU read
  io.rfu_rs1_idx := d.rs1_idx
  io.rfu_rs2_idx := d.rs2_idx

  // ROB forward tag
  io.rob_fwd1.tag := io.rat.read1.tag
  io.rob_fwd2.tag := io.rat.read2.tag

  // --- Source 1 rename ---
  val rename_src1 = Wire(new IQSrcBundle)

  when(!d.needs_rs1) {
    rename_src1.ready := true.B
    rename_src1.value := 0.U
    rename_src1.tag := 0.U
  }.elsewhen(!io.rat.read1.busy) {
    rename_src1.ready := true.B
    rename_src1.value := io.rfu_rs1_v
    rename_src1.tag := 0.U
  }.otherwise {
    val fwd_tag = io.rat.read1.tag
    val fwd_rdy = io.rob_fwd1.valid ||
      (io.cdb1.valid && io.cdb1.tag === fwd_tag) ||
      (io.cdb2.valid && io.cdb2.tag === fwd_tag)
    val fwd_val = MuxCase(
      io.rob_fwd1.value,
      Seq(
        (io.cdb1.valid && io.cdb1.tag === fwd_tag) -> io.cdb1.value,
        (io.cdb2.valid && io.cdb2.tag === fwd_tag) -> io.cdb2.value
      )
    )
    rename_src1.ready := fwd_rdy
    rename_src1.value := fwd_val
    rename_src1.tag := fwd_tag
  }

  // --- Source 2 rename ---
  val rename_src2 = Wire(new IQSrcBundle)

  when(!d.needs_rs2) {
    rename_src2.ready := true.B
    rename_src2.value := 0.U
    rename_src2.tag := 0.U
  }.elsewhen(!io.rat.read2.busy) {
    rename_src2.ready := true.B
    rename_src2.value := io.rfu_rs2_v
    rename_src2.tag := 0.U
  }.otherwise {
    val fwd_tag = io.rat.read2.tag
    val fwd_rdy = io.rob_fwd2.valid ||
      (io.cdb1.valid && io.cdb1.tag === fwd_tag) ||
      (io.cdb2.valid && io.cdb2.tag === fwd_tag)
    val fwd_val = MuxCase(
      io.rob_fwd2.value,
      Seq(
        (io.cdb1.valid && io.cdb1.tag === fwd_tag) -> io.cdb1.value,
        (io.cdb2.valid && io.cdb2.tag === fwd_tag) -> io.cdb2.value
      )
    )
    rename_src2.ready := fwd_rdy
    rename_src2.value := fwd_val
    rename_src2.tag := fwd_tag
  }

  // Dispatch-resolved value computation
  val disp_rd_val = MuxCase(
    0.U,
    Seq(
      d.is_jal -> (d.pc + 4.U),
      d.is_lui -> d.imm,
      d.is_auipc -> (d.pc + d.imm)
    )
  )

  val disp_target_npc = MuxCase(
    0.U,
    Seq(
      d.is_jal -> (d.pc + d.imm),
      d.is_branch -> (d.pc + d.imm)
    )
  )

  // Exception mcause mapping
  val dispatch_mcause = MuxCase(
    0.U,
    Seq(
      d.ifu_exceptionEn -> MuxLookup(d.ifu_exception, 0.U)(
        Seq(
          IFUExceptionType.ifu_INSTRUCTION_ADDRESS_MISALIGNED -> 0.U,
          IFUExceptionType.ifu_INSTRUCTION_ACCESS_FAULT -> 1.U,
          IFUExceptionType.ifu_INSTRUCTION_PAGE_FAULT -> 12.U
        )
      ),
      d.ctrl.exceptionEn -> MuxLookup(d.ctrl.exception, 0.U)(
        Seq(
          CUExceptionType.cu_ILLEGAL_INSTRUCTION -> 2.U,
          CUExceptionType.cu_BREAKPOINT -> 3.U,
          CUExceptionType.cu_ECALL_FROM_U_MODE -> 8.U,
          CUExceptionType.cu_ECALL_FROM_S_MODE -> 9.U,
          CUExceptionType.cu_ECALL_FROM_M_MODE -> 11.U
        )
      )
    )
  )

  // --- Output ---
  o.pc := d.pc
  o.inst := d.inst
  o.imm := d.imm
  o.rd_idx := d.rd_idx
  o.rd_def := d.rd_def
  o.ctrl := d.ctrl
  o.go_to_alu := d.go_to_alu
  o.go_to_bru := d.go_to_bru
  o.go_to_agu := d.go_to_agu
  o.dispatch_except_en := d.dispatch_except_en
  o.dispatch_resolved := d.dispatch_resolved

  o.src1 := rename_src1
  o.src2 := rename_src2

  o.disp_rd_val := disp_rd_val
  o.disp_rd_val_valid := d.is_jal || d.is_lui || d.is_auipc
  o.disp_mispredict := d.is_jal || d.is_mret
  o.disp_target_npc := disp_target_npc
  o.dispatch_mcause := dispatch_mcause
  o.dispatch_mtval := Mux(d.ifu_exceptionEn, d.ifu_mtval, d.ctrl.mtval)
}
