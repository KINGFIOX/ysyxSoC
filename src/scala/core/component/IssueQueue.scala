package ysyx.core.component

import chisel3._
import chisel3.util._
import ysyx.core.common._

class CDBBundle extends NPCBundle {
  val valid = Bool()
  val tag = UInt(robEntryBits.W)
  val value = UInt(dataBits.W)
}

class IQSrcBundle extends NPCBundle {
  val value = UInt(dataBits.W)
  val tag = UInt(robEntryBits.W)
  val ready = Bool()
}

class IQEnqData[T <: Data](gen: T) extends NPCBundle {
  val src1 = new IQSrcBundle
  val src2 = new IQSrcBundle
  val extra = gen.cloneType
  val rob_tag = UInt(robEntryBits.W)
}

class IQIssueData[T <: Data](gen: T) extends NPCBundle {
  val src1_v = UInt(dataBits.W)
  val src2_v = UInt(dataBits.W)
  val extra = gen.cloneType
  val rob_tag = UInt(robEntryBits.W)
}

// for Imm, always ready
class IQEntry[T <: Data](gen: T) extends NPCBundle {
  val src1_val = UInt(dataBits.W)
  val src1_tag = UInt(robEntryBits.W)
  val src1_ready = Bool()
  val src2_val = UInt(dataBits.W)
  val src2_tag = UInt(robEntryBits.W)
  val src2_ready = Bool()
  val extra = gen.cloneType
  val rob_tag = UInt(robEntryBits.W)
  val occupied = Bool()
}

abstract class IssueQueue[T <: Data](gen: T, val entries: Int)
    extends NPCModule {
  require(isPow2(entries))
  private val idxBits = log2Ceil(entries)

  val io = IO(new Bundle {
    val enq = Flipped(Decoupled(new IQEnqData(gen)))
    val issue = Decoupled(new IQIssueData(gen))
    val cdb1 = Flipped(new CDBBundle)
    val cdb2 = Flipped(new CDBBundle)
    val flush = Input(Bool())
  })

  val ram = Reg(Vec(entries, new IQEntry(gen)))
  val enq_ptr = RegInit(0.U(idxBits.W))
  val deq_ptr = RegInit(0.U(idxBits.W))
  val maybe_full = RegInit(false.B)
  val ptr_match = enq_ptr === deq_ptr
  val empty = ptr_match && !maybe_full
  val full = ptr_match && maybe_full

  // CDB value capture: write to ram, ready on next cycle
  // format: off
  ram.foreach(ent =>
    when(ent.occupied && !io.flush) {
      when(io.cdb1.valid && !ent.src1_ready && ent.src1_tag === io.cdb1.tag) {
        ent.src1_val := io.cdb1.value
        ent.src1_ready := true.B
        assert(!ent.src1_ready, "impossible: src1 already ready on CDB hit")
      }.elsewhen( io.cdb2.valid && !ent.src1_ready && ent.src1_tag === io.cdb2.tag) {
        ent.src1_val := io.cdb2.value
        ent.src1_ready := true.B
        assert(!ent.src1_ready, "impossible: src1 already ready on CDB hit")
      }
      when(io.cdb1.valid && !ent.src2_ready && ent.src2_tag === io.cdb1.tag) {
        ent.src2_val := io.cdb1.value
        ent.src2_ready := true.B
        assert(!ent.src2_ready, "impossible: src2 already ready on CDB hit")
      }.elsewhen( io.cdb2.valid && !ent.src2_ready && ent.src2_tag === io.cdb2.tag) {
        ent.src2_val := io.cdb2.value
        ent.src2_ready := true.B
        assert(!ent.src2_ready, "impossible: src2 already ready on CDB hit")
      }
    }
  )
  // format: on

  // Issue from head: in-order, gated by source readiness
  val head_ent = ram(deq_ptr)
  io.issue.valid := !empty && head_ent.src1_ready && head_ent.src2_ready && !io.flush
  io.issue.bits.rob_tag := head_ent.rob_tag
  io.issue.bits.src1_v := head_ent.src1_val
  io.issue.bits.src2_v := head_ent.src2_val
  io.issue.bits.extra := head_ent.extra

  // Enqueue at tail
  io.enq.ready := !full && !io.flush

  val do_enq = io.enq.fire
  val do_deq = io.issue.fire

  when(do_enq) {
    val ent = ram(enq_ptr)
    ent.src1_val := io.enq.bits.src1.value
    ent.src1_tag := io.enq.bits.src1.tag
    ent.src1_ready := io.enq.bits.src1.ready
    ent.src2_val := io.enq.bits.src2.value
    ent.src2_tag := io.enq.bits.src2.tag
    ent.src2_ready := io.enq.bits.src2.ready
    ent.extra := io.enq.bits.extra
    ent.rob_tag := io.enq.bits.rob_tag
    ent.occupied := true.B
    enq_ptr := enq_ptr + 1.U
  }

  when(do_deq) {
    ram(deq_ptr).occupied := false.B
    deq_ptr := deq_ptr + 1.U
  }

  when(do_enq =/= do_deq) {
    maybe_full := do_enq
  }

  when(io.flush) {
    enq_ptr := 0.U
    deq_ptr := 0.U
    maybe_full := false.B
    ram.foreach(_.occupied := false.B)
  }
}

abstract class ExecUnit[I <: Data, O <: Data](inGen: I, outGen: O)
    extends NPCModule {
  val io = IO(new Bundle {
    val in = Flipped(Decoupled(inGen))
    val out = Decoupled(outGen)
  })
}

class LateExecIO extends NPCBundle {
  val req = Input(Bool())
  val done = Output(Bool())
  val result = Output(UInt(dataBits.W))
  val result_valid = Output(Bool())
}
