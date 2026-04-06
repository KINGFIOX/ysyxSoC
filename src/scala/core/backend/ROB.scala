package ysyx.core.backend

import chisel3._
import chisel3.util._

import ysyx.core.common._
import ysyx.core.lsu._

class MemRobEntry extends MemInfoBundle

class CsrRobEntry extends NPCBundle {
  val op = CSROpType()
}

class MretRobEntry extends NPCBundle {
  val mepc = UInt(dataBits.W)
}

class BruRobEntry extends NPCBundle {
  val snpc = UInt(addrBits.W)
  val dnpc = UInt(addrBits.W)
  val br_flag = Bool()
}

class JalRobEntry extends NPCBundle {
  val dnpc = UInt(addrBits.W)
  val is_call = Bool()
}

class JalrRobEntry extends NPCBundle {
  val dnpc = UInt(addrBits.W)
  val dnpc_rdy = Bool()
  val is_ret = Bool()
}

class ExceptRobEntry extends NPCBundle {
  val valid = Bool()
  val mcause = UInt(dataBits.W)
  val mtval = UInt(dataBits.W)
}

object RobState extends ChiselEnum {
  val inflight, late, complete = Value
}

class RobEntry extends NPCBundle {
  val pc = UInt(addrBits.W)
  val inst = UInt(instBits.W)
  val inst_type = InstType()
  val rd = new RdRobEntry
  val prs1 = UInt(NRPhyRegBits.W)
  val prs2 = UInt(NRPhyRegBits.W)
  val imm = UInt(dataBits.W)
  val mem = new MemRobEntry
  val csr = new CsrRobEntry
  val mret = new MretRobEntry
  val bru = new BruRobEntry
  val jal = new JalRobEntry
  val jalr = new JalrRobEntry
  val except = new ExceptRobEntry
  val is_mmio = Bool()
  val predict_npc = UInt(addrBits.W)
  val ghr = UInt(ghrBits.W)
  val state = RobState()
}

class RdRobEntry extends NPCBundle {
  val arch_rd = UInt(NRRegbits.W)
  val new_prd = UInt(NRPhyRegBits.W)
  val old_prd = UInt(NRPhyRegBits.W)
  val rd_wen = Bool()
}

class RobEnqData extends NPCBundle {
  val pc = UInt(addrBits.W)
  val inst = UInt(instBits.W)
  val inst_type = InstType()
  val rd = new RdRobEntry
  val prs1 = UInt(NRPhyRegBits.W)
  val prs2 = UInt(NRPhyRegBits.W)
  val imm = UInt(dataBits.W)
  val mem = new MemInfoBundle
  val csr = new CsrRobEntry
  val mret = new MretRobEntry
  val bru = new BruRobEntry
  val jal = new JalRobEntry
  val jalr = new JalrRobEntry
  val except = new ExceptRobEntry
  val predict_npc = UInt(addrBits.W)
  val ghr = UInt(ghrBits.W)
}

class WBALUBundle extends NPCBundle {
  val tag = UInt(robEntryBits.W)
  val alu_result = UInt(dataBits.W)
}

class WBBRUBundle extends NPCBundle {
  val tag = UInt(robEntryBits.W)
  val br_flag = Bool()
}

class CommitBundle extends NPCBundle {
  val tag = UInt(robEntryBits.W)
  val entry = new RobEntry
}

class Rob extends NPCModule {

  private val entries: Int = (1 << robEntryBits)

  val io = IO(new Bundle {
    val enq = Flipped(Decoupled(new RobEnqData))
    val enq_tag = Output(UInt(robEntryBits.W))
    val alu = Flipped(Valid(new WBALUBundle))
    val bru = Flipped(Valid(new WBBRUBundle))
    val late_prs1 = new PRFReadPort
    val late_prs2 = new PRFReadPort
    val lsu = ReqDone(new MemLate)
    val csr = ReqDone(new CsrWriteOnlyPort)
    val commit = ReqDone(new CommitBundle)
    val lsu_wb = Valid(new PRFWritePort)
    val csr_wb = Valid(new PRFWritePort)
    val flush = Input(Bool())
  })

  val flush = io.flush

  val ram = Reg(Vec(entries, new RobEntry))

  val head_q = RegInit(0.U(robEntryBits.W))
  val tail_q = RegInit(0.U(robEntryBits.W))
  val count_q = RegInit(0.U((robEntryBits + 1).W))

  val empty_w = count_q === 0.U
  val full_w = count_q === entries.U

  private def idx(tag: UInt): UInt = tag(robEntryBits - 1, 0)

  // ============================================================
  // Writeback
  // ============================================================
  when(io.alu.valid && !flush) {
    val ent = ram(idx(io.alu.bits.tag))
    val inst_type = ent.inst_type
    val alu_result = io.alu.bits.alu_result
    // format: off
    when(Seq(InstType.R_ALU, InstType.I_ALU).map(inst_type === _).reduce(_ || _)) {
      ent.state := RobState.complete
    }.elsewhen(inst_type === InstType.JALR) {
      ent.jalr.dnpc := alu_result
      ent.jalr.dnpc_rdy := true.B
      ent.state := RobState.complete
    }
    // format: on
  }
  when(io.bru.valid && !flush) {
    val ent = ram(idx(io.bru.bits.tag))
    ent.bru.br_flag := io.bru.bits.br_flag
    ent.state := RobState.complete
  }

  // ============================================================
  // Enqueue
  // ============================================================
  val enq_fire = io.enq.fire
  io.enq.ready := !full_w && !flush
  io.enq_tag := tail_q.pad(robEntryBits)

  when(enq_fire) {
    val enq = io.enq.bits
    val ent = ram(tail_q)
    ent.pc := enq.pc
    ent.inst := enq.inst
    ent.inst_type := enq.inst_type
    ent.prs1 := enq.prs1
    ent.prs2 := enq.prs2
    ent.imm := enq.imm
    ent.mem := enq.mem
    ent.csr := enq.csr
    ent.rd.arch_rd := enq.rd.arch_rd
    ent.rd.new_prd := enq.rd.new_prd
    ent.rd.old_prd := enq.rd.old_prd
    ent.rd.rd_wen := enq.rd.rd_wen
    ent.except := enq.except
    ent.mret := enq.mret
    ent.bru := enq.bru
    ent.jal := enq.jal
    ent.jalr := enq.jalr
    ent.jalr.dnpc_rdy := false.B
    ent.predict_npc := enq.predict_npc
    ent.ghr := enq.ghr
    // format: off
    val go_to_iq = Seq(InstType.R_ALU, InstType.I_ALU,
      InstType.JALR, InstType.BRANCH)
      .map(enq.inst_type === _).reduce(_ || _) && !enq.except.valid
    val go_to_late = Seq(InstType.LOAD, InstType.STORE, InstType.CSR)
      .map(enq.inst_type === _).reduce(_ || _) && !enq.except.valid
    // format: on
    ent.state := MuxCase(RobState.complete, Seq(
      go_to_iq -> RobState.inflight,
      go_to_late -> RobState.late
    ))
  }

  // ============================================================
  // Late execution at head — operands read from PRF
  // ============================================================
  val head_entry = ram(head_q)
  val head_is_late = !empty_w && head_entry.state === RobState.late
  val head_is_mem = head_entry.mem.r_en || head_entry.mem.w_en
  val head_is_csr = head_entry.inst_type === InstType.CSR

  // PRF read for late exec (BackEnd connects these to PRF ports 4-5)
  io.late_prs1.addr := head_entry.prs1
  io.late_prs2.addr := head_entry.prs2

  val late_addr = io.late_prs1.data + head_entry.imm

  // Drive LSU
  io.lsu.bits.addr := late_addr
  io.lsu.bits.size := head_entry.mem.size
  io.lsu.bits.sign_ext := head_entry.mem.sign_ext
  io.lsu.bits.r_en := head_entry.mem.r_en
  io.lsu.bits.w_en := head_entry.mem.w_en
  io.lsu.bits.wdata := io.late_prs2.data
  io.lsu.bits.is_mmio := AddressMap.is_mmio(late_addr)

  // Drive CSRU: addr from imm, wdata from PRF(prs1)
  io.csr.bits.addr := head_entry.imm(NRCSRbits - 1, 0)
  io.csr.bits.op := head_entry.csr.op
  io.csr.bits.wdata := io.late_prs1.data

  io.lsu.req := head_is_late && head_is_mem && !flush
  io.csr.req := head_is_late && head_is_csr && !flush
  io.csr.bits.wen := head_is_late && head_is_csr && !flush

  // LSU writeback → PRF directly
  io.lsu_wb.valid := false.B
  io.lsu_wb.bits.addr := head_entry.rd.new_prd
  io.lsu_wb.bits.data := 0.U

  // CSR writeback → PRF directly
  io.csr_wb.valid := false.B
  io.csr_wb.bits.addr := head_entry.rd.new_prd
  io.csr_wb.bits.data := 0.U

  when(head_is_late && head_is_mem && io.lsu.done && !flush) {
    head_entry.state := RobState.complete
    head_entry.is_mmio := io.lsu.bits.is_mmio
    when(io.lsu.bits.rd_wen) {
      io.lsu_wb.valid := true.B
      io.lsu_wb.bits.data := io.lsu.bits.result
    }
  }
  when(head_is_late && head_is_csr && io.csr.done && !flush) {
    head_entry.state := RobState.complete
    when(head_entry.rd.rd_wen) {
      io.csr_wb.valid := true.B
      io.csr_wb.bits.data := io.csr.bits.result
    }
  }

  // ============================================================
  // Commit
  // ============================================================
  io.commit.req := !empty_w && head_entry.state === RobState.complete
  io.commit.bits.tag := head_q.pad(robEntryBits)
  io.commit.bits.entry := head_entry

  val deq_fire = io.commit.fire
  when(deq_fire && !io.flush) {
    head_q := head_q + 1.U
  }

  // ============================================================
  // Flush
  // ============================================================
  when(io.flush) {
    head_q := 0.U
    tail_q := 0.U
    count_q := 0.U
  }

  // ============================================================
  // Pointer / count tracking
  // ============================================================
  when(!io.flush) {
    when(enq_fire && !deq_fire) {
      tail_q := tail_q + 1.U
      count_q := count_q + 1.U
    }.elsewhen(!enq_fire && deq_fire) {
      count_q := count_q - 1.U
    }.elsewhen(enq_fire && deq_fire) {
      tail_q := tail_q + 1.U
    }
  }
}

abstract class LateExecUnit[T <: Data](gen: => T) extends NPCModule {
  val late = IO(Flipped(ReqDone(gen)))
}
