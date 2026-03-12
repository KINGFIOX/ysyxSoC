package ysyx.core.component

import chisel3._
import chisel3.util._
import ysyx.core.common._

class CDBBundle extends Bundle with HasCoreParameter {
  val valid = Bool()
  val tag   = UInt(robEntryBits.W)
  val value = UInt(dataBits.W)
}

class IQEntry extends Bundle with HasCoreParameter with HasRegFileParameter {
  val valid     = Bool()
  val rs1_val   = UInt(dataBits.W)
  val rs1_tag   = UInt(robEntryBits.W)
  val rs1_ready = Bool()
  val rs2_val   = UInt(dataBits.W)
  val rs2_tag   = UInt(robEntryBits.W)
  val rs2_ready = Bool()

  val aluOp   = ALUOpType()
  val aluSel1 = ALUOp1Sel()
  val aluSel2 = ALUOp2Sel()
  val npcOp   = NPCOpType()
  val bruOp   = BRUOpType()
  val wbSel   = WBSel()

  val imm     = UInt(dataBits.W)
  val pc      = UInt(addrBits.W)
  val rob_tag = UInt(robEntryBits.W)
  val rd_def  = Bool()
}

class IQEnqData extends Bundle with HasCoreParameter with HasRegFileParameter {
  val rs1_val   = UInt(dataBits.W)
  val rs1_tag   = UInt(robEntryBits.W)
  val rs1_ready = Bool()
  val rs2_val   = UInt(dataBits.W)
  val rs2_tag   = UInt(robEntryBits.W)
  val rs2_ready = Bool()

  val aluOp   = ALUOpType()
  val aluSel1 = ALUOp1Sel()
  val aluSel2 = ALUOp2Sel()
  val npcOp   = NPCOpType()
  val bruOp   = BRUOpType()
  val wbSel   = WBSel()

  val imm     = UInt(dataBits.W)
  val pc      = UInt(addrBits.W)
  val rob_tag = UInt(robEntryBits.W)
  val rd_def  = Bool()
}

class IssueQueue(val entries: Int = 8) extends NPCModule {
  require(isPow2(entries))
  private val idxBits = log2Ceil(entries)

  val io = IO(new Bundle {
    val enq = new Bundle {
      val valid = Input(Bool())
      val ready = Output(Bool())
      val data  = Input(new IQEnqData)
    }

    val issue = new Bundle {
      val valid   = Output(Bool())
      val rob_tag = Output(UInt(robEntryBits.W))
      val op1     = Output(UInt(dataBits.W))
      val op2     = Output(UInt(dataBits.W))
      val aluOp   = Output(ALUOpType())
      val rs1_v   = Output(UInt(dataBits.W))
      val rs2_v   = Output(UInt(dataBits.W))
      val bruOp   = Output(BRUOpType())
      val npcOp   = Output(NPCOpType())
      val wbSel   = Output(WBSel())
      val pc      = Output(UInt(addrBits.W))
      val rd_def  = Output(Bool())
    }

    val cdb1 = Input(new CDBBundle)
    val cdb2 = Input(new CDBBundle)

    val flush = Input(Bool())
  })

  // ---- storage ----
  val ram = Reg(Vec(entries, new IQEntry))

  // ---- pointers ----
  val head  = RegInit(0.U(idxBits.W))
  val tail  = RegInit(0.U(idxBits.W))
  val count = RegInit(0.U((idxBits + 1).W))

  val empty = count === 0.U
  val full  = count === entries.U

  // ============================================================
  // CDB value capture
  // ============================================================
  for (i <- 0 until entries) {
    val e = ram(i)
    when(e.valid && !io.flush) {
      val cap_s1_c1 = io.cdb1.valid && !e.rs1_ready && e.rs1_tag === io.cdb1.tag
      val cap_s1_c2 = io.cdb2.valid && !e.rs1_ready && e.rs1_tag === io.cdb2.tag
      val cap_s2_c1 = io.cdb1.valid && !e.rs2_ready && e.rs2_tag === io.cdb1.tag
      val cap_s2_c2 = io.cdb2.valid && !e.rs2_ready && e.rs2_tag === io.cdb2.tag

      when(cap_s1_c1) {
        e.rs1_val   := io.cdb1.value
        e.rs1_ready := true.B
      }.elsewhen(cap_s1_c2) {
        e.rs1_val   := io.cdb2.value
        e.rs1_ready := true.B
      }

      when(cap_s2_c1) {
        e.rs2_val   := io.cdb1.value
        e.rs2_ready := true.B
      }.elsewhen(cap_s2_c2) {
        e.rs2_val   := io.cdb2.value
        e.rs2_ready := true.B
      }
    }
  }

  // ============================================================
  // Issue select — oldest entry with valid && both sources ready
  // ============================================================
  // CDB2 bypass is safe (commit-time, no loop). CDB1 bypass is NOT
  // used here to avoid a combinational cycle: CDB1 depends on issue output.
  val rotated_ready = VecInit((0 until entries).map { off =>
    val actual = (head +& off.U)(idxBits - 1, 0)
    val in_range = off.U((idxBits + 1).W) < count
    val e = ram(actual)
    val s1_rdy = e.rs1_ready ||
      (io.cdb2.valid && !e.rs1_ready && e.rs1_tag === io.cdb2.tag)
    val s2_rdy = e.rs2_ready ||
      (io.cdb2.valid && !e.rs2_ready && e.rs2_tag === io.cdb2.tag)
    in_range && e.valid && s1_rdy && s2_rdy
  })
  val has_issuable  = rotated_ready.asUInt.orR
  val issue_offset  = PriorityEncoder(rotated_ready.asUInt)
  val issue_idx     = (head +& issue_offset)(idxBits - 1, 0)
  val issue_entry   = ram(issue_idx)

  val iss_rs1 = MuxCase(issue_entry.rs1_val, Seq(
    (io.cdb2.valid && !issue_entry.rs1_ready && issue_entry.rs1_tag === io.cdb2.tag) -> io.cdb2.value
  ))
  val iss_rs2 = MuxCase(issue_entry.rs2_val, Seq(
    (io.cdb2.valid && !issue_entry.rs2_ready && issue_entry.rs2_tag === io.cdb2.tag) -> io.cdb2.value
  ))

  io.issue.valid   := has_issuable && !io.flush
  io.issue.rob_tag := issue_entry.rob_tag
  io.issue.aluOp   := issue_entry.aluOp
  io.issue.bruOp   := issue_entry.bruOp
  io.issue.npcOp   := issue_entry.npcOp
  io.issue.wbSel   := issue_entry.wbSel
  io.issue.pc      := issue_entry.pc
  io.issue.rd_def  := issue_entry.rd_def
  io.issue.rs1_v   := iss_rs1
  io.issue.rs2_v   := iss_rs2

  io.issue.op1 := MuxLookup(issue_entry.aluSel1, iss_rs1)(Seq(
    ALUOp1Sel.OP1_RS1  -> iss_rs1,
    ALUOp1Sel.OP1_PC   -> issue_entry.pc,
    ALUOp1Sel.OP1_ZERO -> 0.U
  ))
  io.issue.op2 := Mux(
    issue_entry.aluSel2 === ALUOp2Sel.OP2_IMM,
    issue_entry.imm,
    iss_rs2
  )

  // Invalidate the issued entry
  when(io.issue.valid) {
    ram(issue_idx).valid := false.B
  }

  // ============================================================
  // Enqueue (after issue invalidation so tail write takes priority
  // if tail == issue_idx, which shouldn't happen but is safe)
  // ============================================================
  val enq_fire = io.enq.valid && io.enq.ready
  io.enq.ready := !full && !io.flush

  when(enq_fire) {
    val d = io.enq.data
    val e = ram(tail)
    e.valid     := true.B
    e.rs1_val   := d.rs1_val; e.rs1_tag := d.rs1_tag; e.rs1_ready := d.rs1_ready
    e.rs2_val   := d.rs2_val; e.rs2_tag := d.rs2_tag; e.rs2_ready := d.rs2_ready
    e.aluOp     := d.aluOp;   e.aluSel1 := d.aluSel1; e.aluSel2 := d.aluSel2
    e.npcOp     := d.npcOp;   e.bruOp   := d.bruOp;    e.wbSel   := d.wbSel
    e.imm       := d.imm;     e.pc      := d.pc
    e.rob_tag   := d.rob_tag; e.rd_def  := d.rd_def
  }

  // ============================================================
  // Head advancement — move head past invalidated (issued) entries
  // ============================================================
  val head_advance = !empty && !ram(head).valid

  // ============================================================
  // Pointer / count tracking
  // ============================================================
  when(io.flush) {
    head  := 0.U
    tail  := 0.U
    count := 0.U
    for (i <- 0 until entries) { ram(i).valid := false.B }
  }.otherwise {
    when(enq_fire && !head_advance) {
      tail  := tail + 1.U
      count := count + 1.U
    }.elsewhen(!enq_fire && head_advance) {
      head  := head + 1.U
      count := count - 1.U
    }.elsewhen(enq_fire && head_advance) {
      tail := tail + 1.U
      head := head + 1.U
    }
  }
}
