package ysyx.core.component

import chisel3._
import chisel3.util._
import ysyx.core.common._

class CDBBundle extends NPCBundle {
  val valid = Bool()
  val tag   = UInt(robEntryBits.W)
  val value = UInt(dataBits.W)
}

class IQSrcBundle extends NPCBundle {
  val value = UInt(dataBits.W)
  val tag   = UInt(robEntryBits.W)
  val ready = Bool()
}

class IQEnqData[T <: Data](gen: T) extends NPCBundle {
  val src1    = new IQSrcBundle
  val src2    = new IQSrcBundle
  val extra   = gen.cloneType
  val rob_tag = UInt(robEntryBits.W)
}

class IQIssueData[T <: Data](gen: T) extends NPCBundle {
  val src1_v  = UInt(dataBits.W)
  val src2_v  = UInt(dataBits.W)
  val extra   = gen.cloneType
  val rob_tag = UInt(robEntryBits.W)
}

class IQEntry[T <: Data](gen: T) extends NPCBundle {
  val valid      = Bool()
  val src1_val   = UInt(dataBits.W)
  val src1_tag   = UInt(robEntryBits.W)
  val src1_ready = Bool()
  val src2_val   = UInt(dataBits.W)
  val src2_tag   = UInt(robEntryBits.W)
  val src2_ready = Bool()
  val extra      = gen.cloneType
  val rob_tag    = UInt(robEntryBits.W)
}

abstract class IssueQueue[T <: Data](
    gen: T,
    val entries: Int = 4,
    val bypassCDB1InIssue: Boolean = true
) extends NPCModule {
  require(isPow2(entries))
  private val idxBits = log2Ceil(entries)

  val io = IO(new Bundle {
    val enq   = Flipped(Decoupled(new IQEnqData(gen)))
    val issue = Decoupled(new IQIssueData(gen))
    val cdb1  = Input(new CDBBundle)
    val cdb2  = Input(new CDBBundle)
    val flush = Input(Bool())
  })

  private val ram   = Reg(Vec(entries, new IQEntry(gen)))
  private val head  = RegInit(0.U(idxBits.W))
  private val tail  = RegInit(0.U(idxBits.W))
  private val count = RegInit(0.U((idxBits + 1).W))
  private val empty = count === 0.U
  private val full  = count === entries.U

  // CDB value capture (always both CDB1 and CDB2)
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

  private def srcReady(ready: Bool, tag: UInt): Bool = {
    val rdy = ready || (io.cdb2.valid && !ready && tag === io.cdb2.tag)
    if (bypassCDB1InIssue) rdy || (io.cdb1.valid && !ready && tag === io.cdb1.tag)
    else rdy
  }

  private val rotated_ready = VecInit((0 until entries).map { off =>
    val actual   = (head +& off.U)(idxBits - 1, 0)
    val in_range = off.U((idxBits + 1).W) < count
    val e = ram(actual)
    in_range && e.valid && srcReady(e.src1_ready, e.src1_tag) && srcReady(e.src2_ready, e.src2_tag)
  })

  private val has_issuable = rotated_ready.asUInt.orR
  private val issue_offset = PriorityEncoder(rotated_ready.asUInt)
  private val issue_idx    = (head +& issue_offset)(idxBits - 1, 0)
  private val ie           = ram(issue_idx)

  private def srcBypass(value: UInt, ready: Bool, tag: UInt): UInt = {
    val base = Seq((io.cdb2.valid && !ready && tag === io.cdb2.tag) -> io.cdb2.value)
    val all = if (bypassCDB1InIssue) Seq((io.cdb1.valid && !ready && tag === io.cdb1.tag) -> io.cdb1.value) ++ base else base
    MuxCase(value, all)
  }

  io.issue.valid        := has_issuable && !io.flush
  io.issue.bits.rob_tag := ie.rob_tag
  io.issue.bits.src1_v  := srcBypass(ie.src1_val, ie.src1_ready, ie.src1_tag)
  io.issue.bits.src2_v  := srcBypass(ie.src2_val, ie.src2_ready, ie.src2_tag)
  io.issue.bits.extra   := ie.extra

  when(io.issue.fire) { ram(issue_idx).valid := false.B }

  // Enqueue
  io.enq.ready := !full && !io.flush

  when(io.enq.fire) {
    val e = ram(tail)
    e.valid      := true.B
    e.src1_val   := io.enq.bits.src1.value
    e.src1_tag   := io.enq.bits.src1.tag
    e.src1_ready := io.enq.bits.src1.ready
    e.src2_val   := io.enq.bits.src2.value
    e.src2_tag   := io.enq.bits.src2.tag
    e.src2_ready := io.enq.bits.src2.ready
    e.extra      := io.enq.bits.extra
    e.rob_tag    := io.enq.bits.rob_tag
  }

  // Head advancement + pointer tracking
  private val head_advance = !empty && !ram(head).valid

  when(io.flush) {
    head := 0.U; tail := 0.U; count := 0.U
    for (i <- 0 until entries) { ram(i).valid := false.B }
  }.otherwise {
    when(io.enq.fire && !head_advance) {
      tail := tail + 1.U; count := count + 1.U
    }.elsewhen(!io.enq.fire && head_advance) {
      head := head + 1.U; count := count - 1.U
    }.elsewhen(io.enq.fire && head_advance) {
      tail := tail + 1.U; head := head + 1.U
    }
  }
}

abstract class ExecUnit[I <: Data, O <: Data](inGen: I, outGen: O)
    extends NPCModule {
  val io = IO(new Bundle {
    val in  = Input(inGen)
    val out = Output(outGen)
  })
}

class LateExecIO extends NPCBundle {
  val req          = Input(Bool())
  val done         = Output(Bool())
  val result       = Output(UInt(dataBits.W))
  val result_valid = Output(Bool())
}
