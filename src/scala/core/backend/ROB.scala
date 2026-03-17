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

class RdRobEntry extends NPCBundle {
  val idx = UInt(NRRegbits.W)
  val value = UInt(dataBits.W)
  val valid = Bool()
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
  val rd = new RdRobEntry
  val except = new ExceptRobEntry
  val is_call = Bool()
  val is_ret = Bool()
  val target_npc = UInt(addrBits.W)
  val predict_npc = UInt(addrBits.W)
  val state = RobState()
}

class RobEnqData extends NPCBundle {
  val pc = UInt(addrBits.W)
  val inst = UInt(instBits.W)
  val inst_type = InstType()
  val mem = new MemInfoBundle
  val csr = new CsrRobEntry
  val rd = new RdRobEntry
  val except = new ExceptRobEntry
  val is_call = Bool()
  val is_ret = Bool()
  val target_npc = UInt(addrBits.W)
  val predict_npc = UInt(addrBits.W)
  val state = RobState()
}

class WBALUBundle extends NPCBundle {
  val tag = UInt(robEntryBits.W)
  val rd_value = UInt(dataBits.W)
  val rd_valid = Bool()
  val target_npc = UInt(addrBits.W)
  val csr_wdata = UInt(dataBits.W)
}

class WBAGUBundle extends NPCBundle {
  val tag = UInt(robEntryBits.W)
  val addr = UInt(addrBits.W)
  val wdata = UInt(dataBits.W)
  val is_mmio = Bool()
}

class WBBRUBundle extends NPCBundle {
  val tag = UInt(robEntryBits.W)
  val target_npc = UInt(addrBits.W)
}

class LookupBundle extends NPCBundle {
  val tag = Output(UInt(robEntryBits.W))
  val entry = Input(new RobEntry)
}

// forward to rename stage
class RobFwdBundle extends NPCBundle {
  val tag = Output(UInt(robEntryBits.W))
  val value = Input(UInt(dataBits.W))
  val valid = Input(Bool())
}

// <tag, entry>
class CommitBundle extends NPCBundle {
  val tag = UInt(robEntryBits.W)
  val entry = new RobEntry
}

class Rob(val numFwdPorts: Int = 2, val numLookupPorts: Int = 2)
    extends NPCModule {

  private val entries: Int = (1 << robEntryBits)

  val io = IO(new Bundle {
    val enq = Flipped(Decoupled(new RobEnqData))
    val enq_tag = Output(UInt(robEntryBits.W))
    val alu = Flipped(Valid(new WBALUBundle))
    val agu = Flipped(Valid(new WBAGUBundle))
    val bru = Flipped(Valid(new WBBRUBundle))
    val lsu = ReqDone(new MemLsuInput)
    val csr = ReqDone(new CsrWriteOnlyPort)
    val exec = Vec(numLookupPorts, Flipped(new LookupBundle)) // lookup
    val commit = Decoupled(new CommitBundle)
    val rename = Vec(numFwdPorts, Flipped(new RobFwdBundle))
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
    val ent = ram(idx(io.alu.bits.tag))
    ent.rd.value := io.alu.bits.rd_value
    ent.rd.valid := io.alu.bits.rd_valid
    ent.target_npc := io.alu.bits.target_npc
    ent.csr.wdata := io.alu.bits.csr_wdata
    ent.state := Mux(ent.inst_type === InstType.CSR, RobState.late, RobState.complete)
  }
  when(io.bru.valid && !io.flush) {
    val ent = ram(idx(io.bru.bits.tag))
    ent.target_npc := io.bru.bits.target_npc
    ent.state := RobState.complete
  }
  when(io.agu.valid && !io.flush) {
    val ent = ram(idx(io.agu.bits.tag))
    ent.mem.addr := io.agu.bits.addr
    ent.mem.wdata := io.agu.bits.wdata
    ent.mem.addr_rdy := true.B
    ent.mem.wdata_rdy := true.B
    ent.mem.is_mmio := io.agu.bits.is_mmio
    ent.state := RobState.late
  }

  // ============================================================
  // Lookup ports
  // ============================================================
  for (i <- 0 until numLookupPorts) {
    val lookup = io.exec(i)
    lookup.entry := ram(idx(lookup.tag))
  }

  // ============================================================
  // Enqueue
  // ============================================================
  val enq_fire = io.enq.fire
  io.enq.ready := !full_w && !io.flush
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
    ent.rd := enq.rd
    ent.except := enq.except
    ent.is_call := enq.is_call
    ent.is_ret := enq.is_ret
    ent.target_npc := enq.target_npc
    ent.predict_npc := enq.predict_npc
    ent.state := enq.state
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

  io.lsu.req := head_is_late && head_is_mem && !io.flush
  io.csr.req := head_is_late && head_is_csr && !io.flush
  io.csr.bits.wen := head_is_late && head_is_csr && !io.flush

  when(head_is_late && head_is_mem && io.lsu.done && !io.flush) {
    when(io.lsu.bits.result_valid) {
      head_entry.rd.value := io.lsu.bits.result
      head_entry.rd.valid := true.B
    }
    head_entry.state := RobState.complete
  }
  when(head_is_late && head_is_csr && io.csr.done && !io.flush) {
    head_entry.rd.value := io.csr.bits.result
    head_entry.rd.valid := true.B
    head_entry.state := RobState.complete
  }

  // ============================================================
  // Commit
  // ============================================================
  io.commit.valid := !empty_w && head_entry.state === RobState.complete
  io.commit.bits.tag := head_q.pad(robEntryBits)
  io.commit.bits.entry := head_entry

  val deq_fire = io.commit.fire
  when(deq_fire && !io.flush) {
    head_q := head_q + 1.U
  }

  // ============================================================
  // name Forward ports
  // ============================================================
  for (i <- 0 until numFwdPorts) {
    val fwd = io.rename(i)
    fwd.valid := ram(idx(fwd.tag)).rd.valid
    fwd.value := ram(idx(fwd.tag)).rd.value
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
