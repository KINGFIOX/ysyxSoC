package ysyx.core.backend

import chisel3._
import chisel3.util._

import ysyx.core.common._

class FreeList extends NPCModule {
  val io = IO(new Bundle {
    val alloc = Decoupled(UInt(NRPhyRegBits.W))
    val free  = Flipped(Valid(UInt(NRPhyRegBits.W)))
    val flush = Input(Bool())
    val arch_snapshot = Input(Vec(NRReg, UInt(NRPhyRegBits.W)))
  })

  // Bitmap: 1 = allocated, 0 = free
  // Initial: p0..p31 allocated (identity-mapped by ArchRAT), p32..p63 free
  val bitmap = RegInit(VecInit((0 until NRPhyReg).map(i => (i < NRReg).B)))

  val free_vec = ~bitmap.asUInt
  io.alloc.valid := free_vec.orR && !io.flush
  io.alloc.bits  := PriorityEncoder(free_vec)

  when(io.flush) {
    val next = WireDefault(VecInit(Seq.fill(NRPhyReg)(false.B)))
    for (i <- 0 until NRReg) {
      next(io.arch_snapshot(i)) := true.B
    }
    bitmap := next
  }.otherwise {
    when(io.alloc.fire) {
      bitmap(io.alloc.bits) := true.B
    }
    when(io.free.valid) {
      bitmap(io.free.bits) := false.B
    }
  }
}
