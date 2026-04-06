package ysyx.core.backend

import chisel3._
import chisel3.util._

import ysyx.core.common._

class IQEnqData[T <: Data](gen: T) extends NPCBundle {
  val prs1 = UInt(NRPhyRegBits.W)
  val prs2 = UInt(NRPhyRegBits.W)
  val extra = gen.cloneType
  val rob_tag = UInt(robEntryBits.W)
}

class IQIssueData[T <: Data](gen: T) extends NPCBundle {
  val prs1 = UInt(NRPhyRegBits.W)
  val prs2 = UInt(NRPhyRegBits.W)
  val extra = gen.cloneType
  val rob_tag = UInt(robEntryBits.W)
}

class IQEntry[T <: Data](gen: T) extends NPCBundle {
  val prs1 = UInt(NRPhyRegBits.W)
  val prs2 = UInt(NRPhyRegBits.W)
  val extra = gen.cloneType
  val rob_tag = UInt(robEntryBits.W)
  val occupied = Bool()
}

abstract class IssueQueue[T <: Data](
    gen: T,
    val entries: Int
) extends NPCModule {
  require(isPow2(entries))
  private val idxBits = log2Ceil(entries)

  val io = IO(new Bundle {
    val enq = Flipped(Decoupled(new IQEnqData(gen)))
    val issue = Decoupled(new IQIssueData(gen))
    val busy_read = Vec(2, new BusyTableReadPort)
    val flush = Input(Bool())
  })

  val ram = Reg(Vec(entries, new IQEntry(gen)))
  val enq_ptr = RegInit(0.U(idxBits.W))
  val deq_ptr = RegInit(0.U(idxBits.W))
  val maybe_full = RegInit(false.B)
  val ptr_match = enq_ptr === deq_ptr
  val empty = ptr_match && !maybe_full
  val full = ptr_match && maybe_full

  // Issue from head: check BusyTable for source readiness
  val head_ent = ram(deq_ptr)
  io.busy_read(0).addr := head_ent.prs1
  io.busy_read(1).addr := head_ent.prs2
  val all_src_ready = !io.busy_read(0).busy && !io.busy_read(1).busy
  io.issue.valid := !empty && all_src_ready && !io.flush
  io.issue.bits.prs1 := head_ent.prs1
  io.issue.bits.prs2 := head_ent.prs2
  io.issue.bits.extra := head_ent.extra
  io.issue.bits.rob_tag := head_ent.rob_tag

  // Enqueue at tail
  io.enq.ready := !full && !io.flush

  val do_enq = io.enq.fire
  val do_deq = io.issue.fire

  when(do_enq) {
    val ent = ram(enq_ptr)
    val enq_data = io.enq.bits
    ent.prs1 := enq_data.prs1
    ent.prs2 := enq_data.prs2
    ent.extra := enq_data.extra
    ent.rob_tag := enq_data.rob_tag
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
