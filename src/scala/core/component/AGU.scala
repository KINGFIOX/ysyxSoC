package ysyx.core.component

import chisel3._
import chisel3.util._
import ysyx.core.common._

class AGUInput extends NPCBundle {
  val offset = UInt(addrBits.W)
  val base = UInt(addrBits.W)
}

class AGUOutput extends NPCBundle {
  val addr = UInt(addrBits.W)
}

class AGU extends NPCModule {
  val io = IO(new Bundle {
    val in  = Flipped(new AGUInput)
    val out = new AGUOutput
  })

  io.out.addr := io.in.base + io.in.offset
}

// ================================================================
// AGU Issue Queue
// ================================================================

class AGUIQEntry extends NPCBundle {
  val valid      = Bool()
  val base_val   = UInt(dataBits.W)
  val base_tag   = UInt(robEntryBits.W)
  val base_ready = Bool()
  val wdata_val  = UInt(dataBits.W)
  val wdata_tag  = UInt(robEntryBits.W)
  val wdata_ready = Bool()
  val imm        = UInt(dataBits.W)
  val rob_tag    = UInt(robEntryBits.W)
}

class AGUIQEnqData extends NPCBundle {
  val base_val   = UInt(dataBits.W)
  val base_tag   = UInt(robEntryBits.W)
  val base_ready = Bool()
  val wdata_val  = UInt(dataBits.W)
  val wdata_tag  = UInt(robEntryBits.W)
  val wdata_ready = Bool()
  val imm        = UInt(dataBits.W)
  val rob_tag    = UInt(robEntryBits.W)
}

class AGUIssueQueue(val entries: Int = 4) extends NPCModule {
  require(isPow2(entries))
  private val idxBits = log2Ceil(entries)

  val io = IO(new Bundle {
    val enq = new Bundle {
      val valid = Input(Bool())
      val ready = Output(Bool())
      val data  = Input(new AGUIQEnqData)
    }
    val issue = new Bundle {
      val valid    = Output(Bool())
      val rob_tag  = Output(UInt(robEntryBits.W))
      val base_v   = Output(UInt(dataBits.W))
      val imm      = Output(UInt(dataBits.W))
      val wdata_v  = Output(UInt(dataBits.W))
    }
    val cdb1  = Input(new CDBBundle)
    val cdb2  = Input(new CDBBundle)
    val flush = Input(Bool())
  })

  val ram   = Reg(Vec(entries, new AGUIQEntry))
  val head  = RegInit(0.U(idxBits.W))
  val tail  = RegInit(0.U(idxBits.W))
  val count = RegInit(0.U((idxBits + 1).W))
  val empty = count === 0.U
  val full  = count === entries.U

  // CDB value capture (CDB1 bypass safe: AGU does not produce CDB1)
  for (i <- 0 until entries) {
    val e = ram(i)
    when(e.valid && !io.flush) {
      when(io.cdb1.valid && !e.base_ready && e.base_tag === io.cdb1.tag) {
        e.base_val := io.cdb1.value; e.base_ready := true.B
      }.elsewhen(io.cdb2.valid && !e.base_ready && e.base_tag === io.cdb2.tag) {
        e.base_val := io.cdb2.value; e.base_ready := true.B
      }
      when(io.cdb1.valid && !e.wdata_ready && e.wdata_tag === io.cdb1.tag) {
        e.wdata_val := io.cdb1.value; e.wdata_ready := true.B
      }.elsewhen(io.cdb2.valid && !e.wdata_ready && e.wdata_tag === io.cdb2.tag) {
        e.wdata_val := io.cdb2.value; e.wdata_ready := true.B
      }
    }
  }

  // Issue select — CDB1+CDB2 bypass both safe (AGU issue does not feed back to CDB1)
  val rotated_ready = VecInit((0 until entries).map { off =>
    val actual   = (head +& off.U)(idxBits - 1, 0)
    val in_range = off.U((idxBits + 1).W) < count
    val e = ram(actual)
    val b_rdy = e.base_ready ||
      (io.cdb1.valid && !e.base_ready && e.base_tag === io.cdb1.tag) ||
      (io.cdb2.valid && !e.base_ready && e.base_tag === io.cdb2.tag)
    val w_rdy = e.wdata_ready ||
      (io.cdb1.valid && !e.wdata_ready && e.wdata_tag === io.cdb1.tag) ||
      (io.cdb2.valid && !e.wdata_ready && e.wdata_tag === io.cdb2.tag)
    in_range && e.valid && b_rdy && w_rdy
  })
  val has_issuable = rotated_ready.asUInt.orR
  val issue_offset = PriorityEncoder(rotated_ready.asUInt)
  val issue_idx    = (head +& issue_offset)(idxBits - 1, 0)
  val ie           = ram(issue_idx)

  val iss_base = MuxCase(ie.base_val, Seq(
    (io.cdb1.valid && !ie.base_ready && ie.base_tag === io.cdb1.tag) -> io.cdb1.value,
    (io.cdb2.valid && !ie.base_ready && ie.base_tag === io.cdb2.tag) -> io.cdb2.value
  ))
  val iss_wdata = MuxCase(ie.wdata_val, Seq(
    (io.cdb1.valid && !ie.wdata_ready && ie.wdata_tag === io.cdb1.tag) -> io.cdb1.value,
    (io.cdb2.valid && !ie.wdata_ready && ie.wdata_tag === io.cdb2.tag) -> io.cdb2.value
  ))

  io.issue.valid   := has_issuable && !io.flush
  io.issue.rob_tag := ie.rob_tag
  io.issue.base_v  := iss_base
  io.issue.imm     := ie.imm
  io.issue.wdata_v := iss_wdata

  when(io.issue.valid) { ram(issue_idx).valid := false.B }

  // Enqueue
  val enq_fire = io.enq.valid && io.enq.ready
  io.enq.ready := !full && !io.flush

  when(enq_fire) {
    val d = io.enq.data; val e = ram(tail)
    e.valid       := true.B
    e.base_val    := d.base_val;  e.base_tag := d.base_tag; e.base_ready := d.base_ready
    e.wdata_val   := d.wdata_val; e.wdata_tag := d.wdata_tag; e.wdata_ready := d.wdata_ready
    e.imm         := d.imm
    e.rob_tag     := d.rob_tag
  }

  // Head advancement + pointer tracking
  val head_advance = !empty && !ram(head).valid

  when(io.flush) {
    head := 0.U; tail := 0.U; count := 0.U
    for (i <- 0 until entries) { ram(i).valid := false.B }
  }.otherwise {
    when(enq_fire && !head_advance) {
      tail := tail + 1.U; count := count + 1.U
    }.elsewhen(!enq_fire && head_advance) {
      head := head + 1.U; count := count - 1.U
    }.elsewhen(enq_fire && head_advance) {
      tail := tail + 1.U; head := head + 1.U
    }
  }
}
