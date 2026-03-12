package ysyx.core.component

import chisel3._
import chisel3.util._
import ysyx.core.component.MemInfoBundle
import ysyx.core.common.NPCModule
import ysyx.core.common.HasCoreParameter
import ysyx.core.common.HasRegFileParameter

// Enqueue bundle — directions are from master (dispatch stage) point-of-view.
// After Flipped(Irrevocable(...)), tag becomes Output from ROB, the rest become Input.
class RobEnq extends Bundle with HasCoreParameter with HasRegFileParameter {
  val tag = Input(UInt(robEntryBits.W))
  val mem = Output(new MemInfoBundle)
  val rd_def = Output(Bool())
  val rd_idx = Output(UInt(NRRegbits.W))
  val pc = Output(UInt(addrBits.W))
}

object EntryState extends ChiselEnum {
  val not_ready, lsu_not_ready, lsu_access, ready = Value
}

class RobEntry extends Bundle with HasCoreParameter with HasRegFileParameter {
  val mem = new MemInfoBundle
  val rd_idx = UInt(NRRegbits.W)
  val rd_val = UInt(dataBits.W)
  val rd_def = Bool()
  val mcause = UInt(dataBits.W)
  val except_en = Bool()
  val pc = UInt(addrBits.W)
  val dnpc = UInt(addrBits.W)
  val jump = Bool()
  val state = EntryState()
}

class Rob(val entries: Int = 32) extends NPCModule {
  require(isPow2(entries))

  val io = IO(new Bundle {
    val enq = Flipped(Irrevocable(new RobEnq))
  })

  // ---- storage ----
  val ram = Reg(Vec(entries, new RobEntry))

  // ---- pointers ----
  val tail_ptr = Counter(entries)
  val head_ptr = Counter(entries)

  val ptr_match = tail_ptr.value === head_ptr.value
  val maybe_full = RegInit(false.B)
  val empty = ptr_match && !maybe_full
  val full = ptr_match && maybe_full

  val deq_valid = ! empty && ram(head_ptr.value).state === EntryState.ready
  val deq_ready = true.B // TODO:
  val deq_fire = deq_valid && deq_valid


  val do_enq = io.enq.fire
  val do_deq = deq_fire

  // ---- enqueue (allocate) ----
  io.enq.ready := !full
  io.enq.bits.tag := tail_ptr.value

  when(do_enq) {
    val mem = io.enq.bits.mem
    ram(tail_ptr.value).mem := mem
    ram(tail_ptr.value).rd_idx := io.enq.bits.rd_idx
    ram(tail_ptr.value).rd_def := io.enq.bits.rd_def
    ram(tail_ptr.value).pc := io.enq.bits.pc
    ram(tail_ptr.value).rd_val := 0.U
    ram(tail_ptr.value).mcause := 0.U
    ram(tail_ptr.value).except_en := false.B
    ram(tail_ptr.value).dnpc := 0.U
    ram(tail_ptr.value).jump := false.B
    ram(tail_ptr.value).state := Mux( mem.w_en || mem.r_en, EntryState.lsu_not_ready, EntryState.not_ready )
    tail_ptr.inc()
  }

  when(do_deq) {
    head_ptr.inc()
  }

  // ---- full / empty tracking ----
  when(do_enq =/= do_deq) {
    maybe_full := do_enq
  }

  // ---- occupancy count ----
  val ptr_diff = tail_ptr.value - head_ptr.value
  io.count := Mux(maybe_full && ptr_match, entries.U, 0.U) | ptr_diff
}
