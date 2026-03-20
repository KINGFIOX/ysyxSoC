package ysyx.core.backend

import chisel3._
import chisel3.util._

import ysyx.core.common._

// from the master's point-of-view
class CDBBundle extends NPCBundle {
  val tag = UInt(robEntryBits.W)
  val value = UInt(dataBits.W)
}

class IQSrcBundle extends NPCBundle {
  val value = UInt(dataBits.W)
  val tag = UInt(robEntryBits.W)
  val ready = Bool()
}

class IQEnqData[T <: Data](gen: T, numOps: Int) extends NPCBundle {
  val src = Vec(numOps, new IQSrcBundle)
  val extra = gen.cloneType
  val rob_tag = UInt(robEntryBits.W)
}

class IQIssueData[T <: Data](gen: T, numOps: Int) extends NPCBundle {
  val src_v = Vec(numOps, UInt(dataBits.W))
  val extra = gen.cloneType
  val rob_tag = UInt(robEntryBits.W)
}

// for Imm, always ready
class IQEntry[T <: Data](gen: T, numOps: Int) extends NPCBundle {
  val src = Vec(numOps, new IQSrcBundle)
  val extra = gen.cloneType
  val rob_tag = UInt(robEntryBits.W)
  val occupied = Bool()
}

abstract class IssueQueue[T <: Data](gen: T, val entries: Int, val numOps: Int)
    extends NPCModule {
  require(isPow2(entries))
  private val idxBits = log2Ceil(entries)

  val io = IO(new Bundle {
    val enq = Flipped(Decoupled(new IQEnqData(gen, numOps)))
    val issue = Decoupled(new IQIssueData(gen, numOps))
    val cdb1 = Flipped(Valid(new CDBBundle))
    val cdb2 = Flipped(Valid(new CDBBundle))
    val flush = Input(Bool())
  })

  val ram = Reg(Vec(entries, new IQEntry(gen, numOps)))
  val enq_ptr = RegInit(0.U(idxBits.W))
  val deq_ptr = RegInit(0.U(idxBits.W))
  val maybe_full = RegInit(false.B)
  val ptr_match = enq_ptr === deq_ptr
  val empty = ptr_match && !maybe_full
  val full = ptr_match && maybe_full

  // CDB value capture: write to ram, ready on next cycle
  ram.foreach(ent =>
    when(ent.occupied && !io.flush) {
      val cdb1 = io.cdb1
      val cdb2 = io.cdb2
      for (i <- 0 until numOps) {
        when(
          cdb1.valid && !ent.src(i).ready && ent.src(i).tag === cdb1.bits.tag
        ) {
          ent.src(i).value := cdb1.bits.value
          ent.src(i).ready := true.B
          assert(!ent.src(i).ready, "impossible: src already ready on CDB hit")
        }.elsewhen(
          cdb2.valid && !ent.src(i).ready && ent.src(i).tag === cdb2.bits.tag
        ) {
          ent.src(i).value := cdb2.bits.value
          ent.src(i).ready := true.B
          assert(!ent.src(i).ready, "impossible: src already ready on CDB hit")
        }
      }
    }
  )

  // Issue from head: in-order, gated by source readiness
  val head_ent = ram(deq_ptr)
  val all_src_ready = (0 until numOps).map(i => head_ent.src(i).ready).reduce(_ && _)
  io.issue.valid := !empty && all_src_ready && !io.flush
  io.issue.bits.rob_tag := head_ent.rob_tag
  for (i <- 0 until numOps) {
    io.issue.bits.src_v(i) := head_ent.src(i).value
  }
  io.issue.bits.extra := head_ent.extra

  // Enqueue at tail
  io.enq.ready := !full && !io.flush

  val do_enq = io.enq.fire
  val do_deq = io.issue.fire

  when(do_enq) {
    val ent = ram(enq_ptr)
    val cdb1 = io.cdb1
    val cdb2 = io.cdb2
    for (i <- 0 until numOps) {
      val enq_src = io.enq.bits.src(i)
      val cdb1_hit = cdb1.valid && !enq_src.ready && (enq_src.tag === cdb1.bits.tag)
      val cdb2_hit = cdb2.valid && !enq_src.ready && (enq_src.tag === cdb2.bits.tag)
      ent.src(i).tag := enq_src.tag
      ent.src(i).ready := enq_src.ready || cdb1_hit || cdb2_hit
      ent.src(i).value := MuxCase(
        enq_src.value,
        Seq(
          cdb1_hit -> cdb1.bits.value,
          cdb2_hit -> cdb2.bits.value
        )
      )
    }
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
