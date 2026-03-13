package ysyx.core.component

import chisel3._
import chisel3.util._
import ysyx.core.common._

// whether the entry valided, defined by queue ptr: head and tail
object EntryState extends ChiselEnum {
  val allocated, executed = Value
}

class MemRobEntry extends MemInfoBundle {
  val addr_rdy = Bool()
  val wdata_rdy = Bool()
}

class RobEntry extends NPCBundle {
  val wbSel  = WBSel()
  val mem    = new MemRobEntry
  val csrOp  = CSROpType()
  val csr_wen = Bool()
  val npcOp  = NPCOpType()

  val pc   = UInt(addrBits.W)
  val inst = UInt(InstBits.W)
  val imm  = UInt(dataBits.W)

  val alu_result  = UInt(dataBits.W)
  val rd_val       = UInt(dataBits.W)
  val rd_val_valid = Bool()
  val rd_idx = UInt(NRRegbits.W)
  val rd_def = Bool()

  val except_en = Bool()
  val mcause    = UInt(dataBits.W)
  val xtval     = UInt(dataBits.W)

  val mispredict = Bool()
  val target_npc = UInt(addrBits.W)

  val state = EntryState()
}

class RobEnqData extends NPCBundle {
  val wbSel  = WBSel()
  val mem    = new MemInfoBundle
  val csrOp  = CSROpType()
  val csrWen = Bool()
  val npcOp  = NPCOpType()

  val pc   = UInt(addrBits.W)
  val inst = UInt(InstBits.W)
  val imm  = UInt(dataBits.W)

  val rd_idx = UInt(NRRegbits.W)
  val rd_def = Bool()

  val except_en = Bool()
  val mcause    = UInt(dataBits.W)
  val mtval     = UInt(dataBits.W)

  val dispatch_executed = Bool()
  val rd_val            = UInt(dataBits.W)
  val rd_val_valid      = Bool()
  val mispredict        = Bool()
  val target_npc        = UInt(addrBits.W)
}

class Rob(val entries: Int = 32) extends NPCModule {
  require(isPow2(entries))
  private val idxBits = log2Ceil(entries)

  val io = IO(new Bundle {
    val enq     = Flipped(Decoupled(new RobEnqData))
    val enq_tag = Output(UInt(robEntryBits.W))

    val wb = new Bundle {
      val valid      = Input(Bool())
      val tag        = Input(UInt(robEntryBits.W))
      val alu_result = Input(UInt(dataBits.W))
      val rd_val       = Input(UInt(dataBits.W))
      val rd_val_valid = Input(Bool())
      val mispredict = Input(Bool())
      val target_npc = Input(UInt(addrBits.W))
    }

    val wb_agu = new Bundle {
      val valid = Input(Bool())
      val tag   = Input(UInt(robEntryBits.W))
      val addr  = Input(UInt(addrBits.W))
      val wdata = Input(UInt(dataBits.W))
    }

    val wb2 = new Bundle {
      val valid      = Input(Bool())
      val tag        = Input(UInt(robEntryBits.W))
      val mispredict = Input(Bool())
      val actual_npc = Input(UInt(addrBits.W))
    }

    val lookup1 = new Bundle {
      val tag   = Input(UInt(robEntryBits.W))
      val entry = Output(new RobEntry)
    }
    val lookup2 = new Bundle {
      val tag   = Input(UInt(robEntryBits.W))
      val entry = Output(new RobEntry)
    }

    val commit = new Bundle {
      val valid = Output(Bool())
      val deq   = Input(Bool())
      val tag   = Output(UInt(robEntryBits.W))
      val entry = Output(new RobEntry)
    }

    val commitWb = new Bundle {
      val valid = Input(Bool())
      val tag   = Input(UInt(robEntryBits.W))
      val value = Input(UInt(dataBits.W))
    }

    val fwd1 = new Bundle {
      val tag   = Input(UInt(robEntryBits.W))
      val value = Output(UInt(dataBits.W))
      val ready = Output(Bool())
    }
    val fwd2 = new Bundle {
      val tag   = Input(UInt(robEntryBits.W))
      val value = Output(UInt(dataBits.W))
      val ready = Output(Bool())
    }

    val flush = Input(Bool())
  })

  // ---- storage ----
  val ram = Reg(Vec(entries, new RobEntry))

  // ---- pointers ----
  val head_q    = RegInit(0.U(idxBits.W))
  val tail_q  = RegInit(0.U(idxBits.W))
  val count_q = RegInit(0.U((idxBits + 1).W))

  val empty_w = count_q === 0.U
  val full_w  = count_q === entries.U

  private def idx(tag: UInt): UInt = tag(idxBits - 1, 0)

  // ============================================================
  // Writeback 1 — from ALU path
  // ============================================================
  when(io.wb.valid && !io.flush) {
    val e = ram(idx(io.wb.tag))
    e.alu_result  := io.wb.alu_result
    e.rd_val       := io.wb.rd_val
    e.rd_val_valid := io.wb.rd_val_valid
    e.mispredict  := io.wb.mispredict
    e.target_npc  := io.wb.target_npc
    e.state       := EntryState.executed
  }

  // ============================================================
  // Writeback 2 — from BRU path
  // ============================================================
  when(io.wb2.valid && !io.flush) {
    val e = ram(idx(io.wb2.tag))
    e.mispredict := io.wb2.mispredict
    e.target_npc := io.wb2.actual_npc
    e.state      := EntryState.executed
  }

  // ============================================================
  // Writeback 3 — from AGU path (addr + wdata for load/store)
  // ============================================================
  when(io.wb_agu.valid && !io.flush) {
    val e = ram(idx(io.wb_agu.tag))
    e.mem.addr     := io.wb_agu.addr
    e.mem.wdata    := io.wb_agu.wdata
    e.mem.addr_rdy := true.B
    e.mem.wdata_rdy := true.B
    e.state        := EntryState.executed
  }

  // Commit-time writeback (load / CSR value)
  when(io.commitWb.valid && !io.flush) {
    val i = idx(io.commitWb.tag)
    ram(i).rd_val       := io.commitWb.value
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
  val enq_fire = io.enq.valid && io.enq.ready
  io.enq.ready := !full_w && !io.flush
  io.enq_tag   := tail_q.pad(robEntryBits)

  when(enq_fire) {
    val d = io.enq.bits
    val e = ram(tail_q)
    e.wbSel    := d.wbSel
    e.mem.size := d.mem.size; e.mem.r_en := d.mem.r_en
    e.mem.sign_ext := d.mem.sign_ext; e.mem.w_en := d.mem.w_en
    e.mem.addr := d.mem.addr; e.mem.wdata := d.mem.wdata
    e.mem.addr_rdy := false.B; e.mem.wdata_rdy := false.B
    e.csrOp  := d.csrOp;  e.csr_wen := d.csrWen
    e.npcOp := d.npcOp
    e.pc     := d.pc;     e.inst := d.inst; e.imm := d.imm
    e.rd_idx := d.rd_idx; e.rd_def := d.rd_def

    e.alu_result  := 0.U
    e.rd_val       := d.rd_val
    e.rd_val_valid := d.rd_val_valid

    e.except_en := d.except_en
    e.mcause    := d.mcause
    e.xtval     := d.mtval

    e.mispredict := d.mispredict
    e.target_npc := d.target_npc

    e.state := Mux(d.except_en || d.dispatch_executed,
                   EntryState.executed, EntryState.allocated)
  }

  // ============================================================
  // Commit — expose head entry
  // ============================================================
  val head_entry = ram(head_q)
  io.commit.valid := !empty_w && head_entry.state === EntryState.executed
  io.commit.tag   := head_q.pad(robEntryBits)
  io.commit.entry := head_entry

  val deq_fire = io.commit.deq && io.commit.valid
  when(deq_fire && !io.flush) {
    head_q := head_q + 1.U
  }

  // ============================================================
  // Forward ports
  // ============================================================
  io.fwd1.ready := ram(idx(io.fwd1.tag)).rd_val_valid
  io.fwd1.value := ram(idx(io.fwd1.tag)).rd_val
  io.fwd2.ready := ram(idx(io.fwd2.tag)).rd_val_valid
  io.fwd2.value := ram(idx(io.fwd2.tag)).rd_val

  // ============================================================
  // Flush
  // ============================================================
  when(io.flush) {
    head_q    := 0.U
    tail_q  := 0.U
    count_q := 0.U
  }

  // ============================================================
  // Pointer / count tracking
  // ============================================================
  when(!io.flush) {
    when(enq_fire && !deq_fire) {
      tail_q  := tail_q + 1.U
      count_q := count_q + 1.U
    }.elsewhen(!enq_fire && deq_fire) {
      count_q := count_q - 1.U
    }.elsewhen(enq_fire && deq_fire) {
      tail_q := tail_q + 1.U
    }
  }
}

class CDBBundle extends NPCBundle {
  val valid = Bool()
  val tag = UInt(robEntryBits.W)
  val value = UInt(dataBits.W)
}
