package ysyx.core.backend

import chisel3._
import chisel3.util._

import ysyx.core.common._
import ysyx.core.lsu._

class MemRobEntry extends MemInfoBundle {
  val addr_rdy = Bool()
  val wdata_rdy = Bool()
  val is_mmio = Bool()
}

class CsrRobEntry extends NPCBundle {
  val wdata = UInt(dataBits.W)
  val addr = UInt(NRCSRbits.W)
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
  val mem = new MemRobEntry
  val csr = new CsrRobEntry
  val arch_rd = UInt(NRRegbits.W)
  val new_prd = UInt(NRPhyRegBits.W)
  val old_prd = UInt(NRPhyRegBits.W)
  val rd_wen = Bool()
  val late_result = UInt(dataBits.W) // latched load/CSR result for PRF write at commit
  val late_rd_wen = Bool()           // whether late result should be written to PRF
  val mret = new MretRobEntry
  val bru = new BruRobEntry
  val jal = new JalRobEntry
  val jalr = new JalrRobEntry
  val except = new ExceptRobEntry
  val predict_npc = UInt(addrBits.W)
  val ghr = UInt(ghrBits.W)
  val state = RobState()
}

class RobEnqData extends NPCBundle {
  val pc = UInt(addrBits.W)
  val inst = UInt(instBits.W)
  val inst_type = InstType()
  val mem = new MemInfoBundle
  val csr = new CsrRobEntry
  val arch_rd = UInt(NRRegbits.W)
  val new_prd = UInt(NRPhyRegBits.W)
  val old_prd = UInt(NRPhyRegBits.W)
  val rd_wen = Bool()
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

class WBAGUBundle extends NPCBundle {
  val tag = UInt(robEntryBits.W)
  val addr = UInt(addrBits.W)
  val wdata = UInt(dataBits.W)
  val is_mmio = Bool()
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
    val agu = Flipped(Valid(new WBAGUBundle))
    val bru = Flipped(Valid(new WBBRUBundle))
    val lsu = ReqDone(new MemLsuInput)
    val csr = ReqDone(new CsrWriteOnlyPort)
    val commit = ReqDone(new CommitBundle)
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
    when(Seq(InstType.R_ALU, InstType.I_ALU, InstType.R_ALU_W, InstType.I_ALU_W).map(inst_type === _).reduce(_ || _)) {
      ent.state := RobState.complete
    }.elsewhen(inst_type === InstType.JALR) {
      ent.jalr.dnpc := alu_result
      ent.jalr.dnpc_rdy := true.B
      ent.state := RobState.complete
    }.elsewhen(inst_type === InstType.CSR) {
      ent.csr.wdata := alu_result
      ent.state := RobState.late
    }
    // format: on
  }
  when(io.bru.valid && !flush) {
    val ent = ram(idx(io.bru.bits.tag))
    ent.bru.br_flag := io.bru.bits.br_flag
    ent.state := RobState.complete
  }
  when(io.agu.valid && !flush) {
    val ent = ram(idx(io.agu.bits.tag))
    ent.mem.addr := io.agu.bits.addr
    ent.mem.wdata := io.agu.bits.wdata
    ent.mem.addr_rdy := true.B
    ent.mem.wdata_rdy := true.B
    ent.mem.is_mmio := io.agu.bits.is_mmio
    ent.state := RobState.late
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
    ent.mem.size := enq.mem.size
    ent.mem.r_en := enq.mem.r_en
    ent.mem.sign_ext := enq.mem.sign_ext
    ent.mem.w_en := enq.mem.w_en
    ent.mem.addr := enq.mem.addr
    ent.mem.wdata := enq.mem.wdata
    ent.mem.addr_rdy := false.B
    ent.mem.wdata_rdy := false.B
    ent.mem.is_mmio := false.B
    ent.csr := enq.csr
    ent.arch_rd := enq.arch_rd
    ent.new_prd := enq.new_prd
    ent.old_prd := enq.old_prd
    ent.rd_wen := enq.rd_wen
    ent.except := enq.except
    ent.mret := enq.mret
    ent.bru := enq.bru
    ent.jal := enq.jal
    ent.jalr := enq.jalr
    ent.jalr.dnpc_rdy := false.B
    ent.late_result := 0.U
    ent.late_rd_wen := false.B
    ent.predict_npc := enq.predict_npc
    ent.ghr := enq.ghr
    // format: off
    val go_to_fu = Seq(InstType.R_ALU, InstType.I_ALU, InstType.R_ALU_W, InstType.I_ALU_W, InstType.JALR,
      InstType.CSR, InstType.BRANCH, InstType.LOAD, InstType.STORE)
      .map(enq.inst_type === _).reduce(_ || _) && !enq.except.valid
    // format: on
    ent.state := Mux(go_to_fu, RobState.inflight, RobState.complete)
  }

  // ============================================================
  // Late execution at head
  // ============================================================
  val head_entry = ram(head_q)
  val head_is_late = !empty_w && head_entry.state === RobState.late
  val head_is_mem = head_entry.mem.r_en || head_entry.mem.w_en
  val head_is_csr = head_entry.inst_type === InstType.CSR

  io.lsu.bits.addr := head_entry.mem.addr
  io.lsu.bits.size := head_entry.mem.size
  io.lsu.bits.sign_ext := head_entry.mem.sign_ext
  io.lsu.bits.r_en := head_entry.mem.r_en
  io.lsu.bits.w_en := head_entry.mem.w_en
  io.lsu.bits.wdata := head_entry.mem.wdata
  io.lsu.bits.is_mmio := head_entry.mem.is_mmio

  io.csr.bits.addr := head_entry.csr.addr
  io.csr.bits.op := head_entry.csr.op
  io.csr.bits.wdata := head_entry.csr.wdata

  io.lsu.req := head_is_late && head_is_mem && !flush
  io.csr.req := head_is_late && head_is_csr && !flush
  io.csr.bits.wen := head_is_late && head_is_csr && !flush

  when(head_is_late && head_is_mem && io.lsu.done && !flush) {
    head_entry.state := RobState.complete
    when(io.lsu.bits.result_valid) {
      head_entry.late_result := io.lsu.bits.result
      head_entry.late_rd_wen := true.B
    }
  }
  when(head_is_late && head_is_csr && io.csr.done && !flush) {
    head_entry.state := RobState.complete
    head_entry.late_result := io.csr.bits.result
    when(head_entry.rd_wen) {
      head_entry.late_rd_wen := true.B
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
