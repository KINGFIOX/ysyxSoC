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
}

class RobEntry extends NPCBundle {
  val mem = new MemRobEntry
  val csr_op = CSROpType()
  val csr_wen = Bool()

  val pc = UInt(addrBits.W)
  val inst = UInt(instBits.W)
  val imm = UInt(dataBits.W)

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
  val imm = UInt(dataBits.W)

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
    val d = io.enq.bits
    val e = ram(tail_q)
    e.mem.size := d.mem.size; e.mem.r_en := d.mem.r_en
    e.mem.sign_ext := d.mem.sign_ext; e.mem.w_en := d.mem.w_en
    e.mem.addr := d.mem.addr; e.mem.wdata := d.mem.wdata
    e.mem.addr_rdy := false.B; e.mem.wdata_rdy := false.B
    e.csr_op := d.csr_op; e.csr_wen := d.csr_wen
    e.is_jalr := d.is_jalr; e.is_mret := d.is_mret
    e.pc := d.pc; e.inst := d.inst; e.imm := d.imm
    e.rd_idx := d.rd_idx; e.rd_def := d.rd_def

    e.alu_result := 0.U
    e.rd_val := d.rd_val
    e.rd_val_valid := d.rd_val_valid

    e.except_en := d.except_en
    e.mcause := d.mcause
    e.xtval := d.mtval

    e.mispredict := d.mispredict
    e.target_npc := d.target_npc

    e.state := Mux(
      d.except_en || d.dispatch_executed,
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
