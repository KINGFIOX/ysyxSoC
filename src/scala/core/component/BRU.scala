package ysyx.core.component

import chisel3._
import chisel3.util._
import ysyx.core.common._

object BRUOpType extends ChiselEnum {
  val bru_X, bru_BLT, bru_BLTU, bru_BGE, bru_BGEU, bru_BEQ, bru_BNE = Value
}

class BRUInput extends NPCBundle {
  val rs1_v  = UInt(dataBits.W)
  val rs2_v  = UInt(dataBits.W)
  val op = BRUOpType()
}

class BRUOutput extends NPCBundle {
  val br_flag = Bool()
}

/** @brief
  *   仅用来判断是否要跳转
  */
class BRU extends NPCModule {
  val io = IO(new Bundle {
    val in  = Flipped(new BRUInput)
    val out = new BRUOutput
  })

  io.out.br_flag := false.B // default

  switch(io.in.op) {
    is(BRUOpType.bru_BLT) { when(io.in.rs1_v.asSInt < io.in.rs2_v.asSInt) { io.out.br_flag := true.B } }
    is(BRUOpType.bru_BLTU) { when(io.in.rs1_v < io.in.rs2_v) { io.out.br_flag := true.B } }
    is(BRUOpType.bru_BGE) { when(io.in.rs1_v.asSInt >= io.in.rs2_v.asSInt) { io.out.br_flag := true.B } }
    is(BRUOpType.bru_BGEU) { when(io.in.rs1_v >= io.in.rs2_v) { io.out.br_flag := true.B } }
    is(BRUOpType.bru_BEQ) { when(io.in.rs1_v === io.in.rs2_v) { io.out.br_flag := true.B } }
    is(BRUOpType.bru_BNE) { when(io.in.rs1_v =/= io.in.rs2_v) { io.out.br_flag := true.B } }
    // BRUOpType.bru_X
  }
}


class BRUIQEntry extends NPCBundle {
  val valid = Bool()
  val rs1_val = UInt(dataBits.W)
  val rs1_tag = UInt(robEntryBits.W)
  val rs1_ready = Bool()
  val rs2_val = UInt(dataBits.W)
  val rs2_tag = UInt(robEntryBits.W)
  val rs2_ready = Bool()
  val bruOp = BRUOpType()
  val rob_tag = UInt(robEntryBits.W)
}

class BRUIQEnqData extends NPCBundle {
  val rs1_val = UInt(dataBits.W)
  val rs1_tag = UInt(robEntryBits.W)
  val rs1_ready = Bool()
  val rs2_val = UInt(dataBits.W)
  val rs2_tag = UInt(robEntryBits.W)
  val rs2_ready = Bool()
  val bruOp = BRUOpType()
  val rob_tag = UInt(robEntryBits.W)
}

class BRUIssueQueue(val entries: Int = 4) extends NPCModule {
  require(isPow2(entries))
  private val idxBits = log2Ceil(entries)

  val io = IO(new Bundle {
    val enq = new Bundle {
      val valid = Input(Bool())
      val ready = Output(Bool())
      val data = Input(new BRUIQEnqData)
    }
    val issue = new Bundle {
      val valid = Output(Bool())
      val rob_tag = Output(UInt(robEntryBits.W))
      val rs1_v = Output(UInt(dataBits.W))
      val rs2_v = Output(UInt(dataBits.W))
      val bruOp = Output(BRUOpType())
    }
    val cdb1 = Input(new CDBBundle)
    val cdb2 = Input(new CDBBundle)
    val flush = Input(Bool())
  })

  val ram = Reg(Vec(entries, new BRUIQEntry))
  val head = RegInit(0.U(idxBits.W))
  val tail = RegInit(0.U(idxBits.W))
  val count = RegInit(0.U((idxBits + 1).W))
  val empty = count === 0.U
  val full = count === entries.U

  // CDB value capture
  for (i <- 0 until entries) {
    val e = ram(i)
    when(e.valid && !io.flush) {
      when(io.cdb1.valid && !e.rs1_ready && e.rs1_tag === io.cdb1.tag) {
        e.rs1_val := io.cdb1.value; e.rs1_ready := true.B
      }.elsewhen(io.cdb2.valid && !e.rs1_ready && e.rs1_tag === io.cdb2.tag) {
        e.rs1_val := io.cdb2.value; e.rs1_ready := true.B
      }
      when(io.cdb1.valid && !e.rs2_ready && e.rs2_tag === io.cdb1.tag) {
        e.rs2_val := io.cdb1.value; e.rs2_ready := true.B
      }.elsewhen(io.cdb2.valid && !e.rs2_ready && e.rs2_tag === io.cdb2.tag) {
        e.rs2_val := io.cdb2.value; e.rs2_ready := true.B
      }
    }
  }

  // Issue select — CDB1 bypass safe here (BRU issue does not feed back to CDB1)
  val rotated_ready = VecInit((0 until entries).map { off =>
    val actual = (head +& off.U)(idxBits - 1, 0)
    val in_range = off.U((idxBits + 1).W) < count
    val e = ram(actual)
    val s1 = e.rs1_ready ||
      (io.cdb1.valid && !e.rs1_ready && e.rs1_tag === io.cdb1.tag) ||
      (io.cdb2.valid && !e.rs1_ready && e.rs1_tag === io.cdb2.tag)
    val s2 = e.rs2_ready ||
      (io.cdb1.valid && !e.rs2_ready && e.rs2_tag === io.cdb1.tag) ||
      (io.cdb2.valid && !e.rs2_ready && e.rs2_tag === io.cdb2.tag)
    in_range && e.valid && s1 && s2
  })
  val has_issuable = rotated_ready.asUInt.orR
  val issue_offset = PriorityEncoder(rotated_ready.asUInt)
  val issue_idx = (head +& issue_offset)(idxBits - 1, 0)
  val ie = ram(issue_idx)

  val iss_rs1 = MuxCase(
    ie.rs1_val,
    Seq(
      (io.cdb1.valid && !ie.rs1_ready && ie.rs1_tag === io.cdb1.tag) -> io.cdb1.value,
      (io.cdb2.valid && !ie.rs1_ready && ie.rs1_tag === io.cdb2.tag) -> io.cdb2.value
    )
  )
  val iss_rs2 = MuxCase(
    ie.rs2_val,
    Seq(
      (io.cdb1.valid && !ie.rs2_ready && ie.rs2_tag === io.cdb1.tag) -> io.cdb1.value,
      (io.cdb2.valid && !ie.rs2_ready && ie.rs2_tag === io.cdb2.tag) -> io.cdb2.value
    )
  )

  io.issue.valid := has_issuable && !io.flush
  io.issue.rob_tag := ie.rob_tag
  io.issue.bruOp := ie.bruOp
  io.issue.rs1_v := iss_rs1
  io.issue.rs2_v := iss_rs2

  when(io.issue.valid) { ram(issue_idx).valid := false.B }

  // Enqueue
  val enq_fire = io.enq.valid && io.enq.ready
  io.enq.ready := !full && !io.flush

  when(enq_fire) {
    val d = io.enq.data; val e = ram(tail)
    e.valid := true.B
    e.rs1_val := d.rs1_val; e.rs1_tag := d.rs1_tag; e.rs1_ready := d.rs1_ready
    e.rs2_val := d.rs2_val; e.rs2_tag := d.rs2_tag; e.rs2_ready := d.rs2_ready
    e.bruOp := d.bruOp; e.rob_tag := d.rob_tag
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
