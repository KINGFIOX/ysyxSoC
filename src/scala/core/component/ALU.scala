/** @brief
  *   ALU - 算术逻辑单元
  */

package ysyx.core.component

import chisel3._
import chisel3.util._
import ysyx.core.common._

object ALUOpType extends ChiselEnum {
  val alu_X, alu_ADD, alu_SUB, alu_AND, alu_OR, alu_XOR, alu_SLL, alu_SRL, alu_SRA, alu_SLT, alu_SLTU = Value
}

class ALUInput extends NPCBundle {
  val op1   = UInt(dataBits.W)
  val op2   = UInt(dataBits.W)
  val aluOp = ALUOpType()
}

class ALUOutput extends NPCBundle {
  val result = UInt(dataBits.W)
}

class ALU extends NPCModule {
  val io = IO(new Bundle {
    val in  = Flipped(new ALUInput)
    val out = new ALUOutput
  })

  private val op1   = io.in.op1
  private val op2   = io.in.op2
  private val shamt = op2(log2Up(dataBits) - 1, 0) // 移位量 (低 5 位)

  io.out.result := 0.U // default

  switch(io.in.aluOp) {
    is(ALUOpType.alu_ADD) { io.out.result := op1 + op2 }
    is(ALUOpType.alu_SUB) { io.out.result := op1 - op2 }
    is(ALUOpType.alu_AND) { io.out.result := op1 & op2 }
    is(ALUOpType.alu_OR) { io.out.result := op1 | op2 }
    is(ALUOpType.alu_XOR) { io.out.result := op1 ^ op2 }
    is(ALUOpType.alu_SLL) { io.out.result := op1 << shamt }
    is(ALUOpType.alu_SRL) { io.out.result := op1 >> shamt }
    is(ALUOpType.alu_SRA) { io.out.result := (op1.asSInt >> shamt).asUInt }
    is(ALUOpType.alu_SLT) { io.out.result := op1.asSInt < op2.asSInt }
    is(ALUOpType.alu_SLTU) { io.out.result := op1 < op2 }
    // alu_X
  }
}

class ALUIQEntry extends NPCBundle {
  val valid = Bool()
  val src1_val = UInt(dataBits.W)
  val src1_tag = UInt(robEntryBits.W)
  val src1_ready = Bool()
  val src2_val = UInt(dataBits.W)
  val src2_tag = UInt(robEntryBits.W)
  val src2_ready = Bool()
  val aluOp = ALUOpType()
  val rob_tag = UInt(robEntryBits.W)
  val rd_def = Bool()
}

class ALUIQEnqData extends NPCBundle {
  val src1_val = UInt(dataBits.W)
  val src1_tag = UInt(robEntryBits.W)
  val src1_ready = Bool()
  val src2_val = UInt(dataBits.W)
  val src2_tag = UInt(robEntryBits.W)
  val src2_ready = Bool()
  val aluOp = ALUOpType()
  val rob_tag = UInt(robEntryBits.W)
  val rd_def = Bool()
}

class ALUIssueQueue(val entries: Int = 8) extends NPCModule {
  require(isPow2(entries))
  private val idxBits = log2Ceil(entries)

  val io = IO(new Bundle {
    val enq = new Bundle {
      val valid = Input(Bool())
      val ready = Output(Bool())
      val data = Input(new ALUIQEnqData)
    }
    val issue = new Bundle {
      val valid = Output(Bool())
      val rob_tag = Output(UInt(robEntryBits.W))
      val op1 = Output(UInt(dataBits.W))
      val op2 = Output(UInt(dataBits.W))
      val aluOp = Output(ALUOpType())
      val src1_v = Output(UInt(dataBits.W))
      val src2_v = Output(UInt(dataBits.W))
      val rd_def = Output(Bool())
    }
    val cdb1 = Input(new CDBBundle)
    val cdb2 = Input(new CDBBundle)
    val flush = Input(Bool())
  })

  val ram = Reg(Vec(entries, new ALUIQEntry))
  val head = RegInit(0.U(idxBits.W))
  val tail = RegInit(0.U(idxBits.W))
  val count = RegInit(0.U((idxBits + 1).W))
  val empty = count === 0.U
  val full = count === entries.U

  // CDB value capture
  for (i <- 0 until entries) {
    val e = ram(i)
    when(e.valid && !io.flush) {
      when(io.cdb1.valid && !e.src1_ready && e.src1_tag === io.cdb1.tag) {
        e.src1_val := io.cdb1.value; e.src1_ready := true.B
      }.elsewhen(io.cdb2.valid && !e.src1_ready && e.src1_tag === io.cdb2.tag) {
        e.src1_val := io.cdb2.value; e.src1_ready := true.B
      }
      when(io.cdb1.valid && !e.src2_ready && e.src2_tag === io.cdb1.tag) {
        e.src2_val := io.cdb1.value; e.src2_ready := true.B
      }.elsewhen(io.cdb2.valid && !e.src2_ready && e.src2_tag === io.cdb2.tag) {
        e.src2_val := io.cdb2.value; e.src2_ready := true.B
      }
    }
  }

  // Issue select — CDB2 bypass safe, CDB1 bypass excluded (combinational cycle)
  val rotated_ready = VecInit((0 until entries).map { off =>
    val actual = (head +& off.U)(idxBits - 1, 0)
    val in_range = off.U((idxBits + 1).W) < count
    val e = ram(actual)
    val s1 = e.src1_ready ||
      (io.cdb2.valid && !e.src1_ready && e.src1_tag === io.cdb2.tag)
    val s2 = e.src2_ready ||
      (io.cdb2.valid && !e.src2_ready && e.src2_tag === io.cdb2.tag)
    in_range && e.valid && s1 && s2
  })
  val has_issuable = rotated_ready.asUInt.orR
  val issue_offset = PriorityEncoder(rotated_ready.asUInt)
  val issue_idx = (head +& issue_offset)(idxBits - 1, 0)
  val ie = ram(issue_idx)

  val iss_s1 = MuxCase(
    ie.src1_val,
    Seq(
      (io.cdb2.valid && !ie.src1_ready && ie.src1_tag === io.cdb2.tag) -> io.cdb2.value
    )
  )
  val iss_s2 = MuxCase(
    ie.src2_val,
    Seq(
      (io.cdb2.valid && !ie.src2_ready && ie.src2_tag === io.cdb2.tag) -> io.cdb2.value
    )
  )

  io.issue.valid := has_issuable && !io.flush
  io.issue.rob_tag := ie.rob_tag
  io.issue.aluOp := ie.aluOp
  io.issue.rd_def := ie.rd_def
  io.issue.src1_v := iss_s1
  io.issue.src2_v := iss_s2
  io.issue.op1 := iss_s1
  io.issue.op2 := iss_s2

  when(io.issue.valid) { ram(issue_idx).valid := false.B }

  // Enqueue
  val enq_fire = io.enq.valid && io.enq.ready
  io.enq.ready := !full && !io.flush

  when(enq_fire) {
    val d = io.enq.data; val e = ram(tail)
    e.valid := true.B
    e.src1_val := d.src1_val; e.src1_tag := d.src1_tag;
    e.src1_ready := d.src1_ready
    e.src2_val := d.src2_val; e.src2_tag := d.src2_tag;
    e.src2_ready := d.src2_ready
    e.aluOp := d.aluOp
    e.rob_tag := d.rob_tag; e.rd_def := d.rd_def
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
