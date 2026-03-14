package ysyx.core.backend

import chisel3._
import chisel3.util._

import ysyx.core.common._
import ysyx.core.lsu._

// whether the entry valided, defined by queue ptr: head and tail
object EntryState extends ChiselEnum {
  val allocated, executed = Value
}

class MemRobEntry extends MemInfoBundle {
  val addr_rdy = Bool()
  val wdata_rdy = Bool()
  val is_mmio = Bool()
}

class RobEntry extends NPCBundle {
  val mem = new MemRobEntry
  val csr_op = CSROpType()
  val csr_wen = Bool()

  val pc = UInt(addrBits.W)
  val inst = UInt(instBits.W)
  val imm = UInt(dataBits.W) // for csr only

  val alu_result = UInt(dataBits.W)
  val rd_val = UInt(dataBits.W)
  val rd_val_valid = Bool()
  val rd_idx = UInt(NRRegbits.W)
  val rd_def = Bool()

  val except_en = Bool()
  val mcause = UInt(dataBits.W)
  val xtval = UInt(dataBits.W)

  val is_jalr = Bool()
  val is_mret = Bool()
  val mispredict = Bool()
  val target_npc = UInt(addrBits.W)

  val state = EntryState()
}

class RobEnqData extends NPCBundle {
  val mem = new MemInfoBundle
  val csr_op = CSROpType()
  val csr_wen = Bool()

  val pc = UInt(addrBits.W)
  val inst = UInt(instBits.W)
  val imm = UInt(dataBits.W) // for csr only

  val rd_idx = UInt(NRRegbits.W)
  val rd_def = Bool()

  val except_en = Bool()
  val mcause = UInt(dataBits.W)
  val mtval = UInt(dataBits.W)

  val is_jalr = Bool()
  val is_mret = Bool()
  val dispatch_executed = Bool()
  val rd_val = UInt(dataBits.W)
  val rd_val_valid = Bool()
  val mispredict = Bool()
  val target_npc = UInt(addrBits.W)
}

// alu
class WBALUBundle extends NPCBundle {
  val tag = UInt(robEntryBits.W)
  val alu_result = UInt(dataBits.W)
  val rd_val = UInt(dataBits.W)
  val rd_val_valid = Bool()
  val mispredict = Bool()
  val target_npc = UInt(addrBits.W)
}

// agu
class WBAGUBundle extends NPCBundle {
  val tag = UInt(robEntryBits.W)
  val addr = UInt(addrBits.W)
  val wdata = UInt(dataBits.W)
  val is_mmio = Bool()
}

// bru
class WBBRUBundle extends NPCBundle {
  val tag = UInt(robEntryBits.W)
  val mispredict = Bool()
  val actual_npc = UInt(addrBits.W)
}

class LookupBundle extends NPCBundle {
  val tag = Output(UInt(robEntryBits.W))
  val entry = Input(new RobEntry)
}

// rename ---(tag)--> rob ---(value, valid)--> rename
class RobFwdBundle extends NPCBundle {
  val tag = Input(UInt(robEntryBits.W))
  val value = Output(UInt(dataBits.W))
  val valid = Output(Bool())
}

class CommitBundle extends NPCBundle {
  val tag = UInt(robEntryBits.W)
  val entry = new RobEntry
}

// write back on commit
class WBCommitBundle extends NPCBundle {
  val tag = UInt(robEntryBits.W)
  val value = UInt(dataBits.W)
}

class Rob extends NPCModule {
  private val entries: Int = (1 << robEntryBits)

  val io = IO(new Bundle {
    val enq = Flipped(Decoupled(new RobEnqData))
    val enq_tag = Output(UInt(robEntryBits.W))
    val alu = Flipped(Valid(new WBALUBundle))
    val agu = Flipped(Valid(new WBAGUBundle))
    val bru = Flipped(Valid(new WBBRUBundle))
    val wb_commit = Flipped(Valid(new WBCommitBundle))
    val lookup1 = Flipped(new LookupBundle)
    val lookup2 = Flipped(new LookupBundle)
    val commit = Decoupled(new CommitBundle)
    val fwd1 = new RobFwdBundle
    val fwd2 = new RobFwdBundle
    val flush = Input(Bool())
  })

  // ---- storage ----
  val ram = Reg(Vec(entries, new RobEntry))

  // ---- pointers ----
  val head_q = RegInit(0.U(robEntryBits.W))
  val tail_q = RegInit(0.U(robEntryBits.W))
  val count_q = RegInit(0.U((robEntryBits + 1).W))

  val empty_w = count_q === 0.U
  val full_w = count_q === entries.U

  private def idx(tag: UInt): UInt = tag(robEntryBits - 1, 0)

  // ============================================================
  // Writeback
  // ============================================================
  when(io.alu.valid && !io.flush) {
    val e = ram(idx(io.alu.bits.tag))
    e.alu_result := io.alu.bits.alu_result
    e.rd_val := io.alu.bits.rd_val
    e.rd_val_valid := io.alu.bits.rd_val_valid
    e.mispredict := io.alu.bits.mispredict
    e.target_npc := io.alu.bits.target_npc
    e.state := EntryState.executed
  }
  when(io.bru.valid && !io.flush) {
    val e = ram(idx(io.bru.bits.tag))
    e.mispredict := io.bru.bits.mispredict
    e.target_npc := io.bru.bits.actual_npc
    e.state := EntryState.executed
  }
  when(io.agu.valid && !io.flush) {
    val e = ram(idx(io.agu.bits.tag))
    e.mem.addr := io.agu.bits.addr
    e.mem.wdata := io.agu.bits.wdata
    e.mem.addr_rdy := true.B
    e.mem.wdata_rdy := true.B
    e.mem.is_mmio := io.agu.bits.is_mmio
    e.state := EntryState.executed
  }

  // Commit-time writeback (load / CSR value)
  when(io.wb_commit.valid && !io.flush) {
    val i = idx(io.wb_commit.bits.tag)
    ram(i).rd_val := io.wb_commit.bits.value
    ram(i).rd_val_valid := true.B
  }

  // ============================================================
  // Lookup ports — combinational read for writeback logic
  // ============================================================
  io.lookup1.entry := ram(idx(io.lookup1.tag))
  io.lookup2.entry := ram(idx(io.lookup2.tag))

  // ============================================================
  // Enqueue
  // ============================================================
  val enq_fire = io.enq.fire
  io.enq.ready := !full_w && !io.flush
  io.enq_tag := tail_q.pad(robEntryBits)

  when(enq_fire) {
    val enq = io.enq.bits
    val ent = ram(tail_q)
    ent.mem.size := enq.mem.size; ent.mem.r_en := enq.mem.r_en
    ent.mem.sign_ext := enq.mem.sign_ext; ent.mem.w_en := enq.mem.w_en
    ent.mem.addr := enq.mem.addr; ent.mem.wdata := enq.mem.wdata
    ent.mem.addr_rdy := false.B; ent.mem.wdata_rdy := false.B; ent.mem.is_mmio := false.B
    ent.csr_op := enq.csr_op; ent.csr_wen := enq.csr_wen
    ent.is_jalr := enq.is_jalr; ent.is_mret := enq.is_mret
    ent.pc := enq.pc; ent.inst := enq.inst; ent.imm := enq.imm
    ent.rd_idx := enq.rd_idx; ent.rd_def := enq.rd_def

    ent.alu_result := 0.U
    ent.rd_val := enq.rd_val
    ent.rd_val_valid := enq.rd_val_valid

    ent.except_en := enq.except_en
    ent.mcause := enq.mcause
    ent.xtval := enq.mtval

    ent.mispredict := enq.mispredict
    ent.target_npc := enq.target_npc

    ent.state := Mux(
      enq.except_en || enq.dispatch_executed,
      EntryState.executed,
      EntryState.allocated
    )
  }

  // ============================================================
  // Commit — expose head entry
  // ============================================================
  val head_entry = ram(head_q)
  io.commit.valid := !empty_w && head_entry.state === EntryState.executed
  io.commit.bits.tag := head_q.pad(robEntryBits)
  io.commit.bits.entry := head_entry

  val deq_fire = io.commit.fire
  when(deq_fire && !io.flush) {
    head_q := head_q + 1.U
  }

  // ============================================================
  // Forward ports
  // ============================================================
  io.fwd1.valid := ram(idx(io.fwd1.tag)).rd_val_valid
  io.fwd1.value := ram(idx(io.fwd1.tag)).rd_val
  io.fwd2.valid := ram(idx(io.fwd2.tag)).rd_val_valid
  io.fwd2.value := ram(idx(io.fwd2.tag)).rd_val

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
