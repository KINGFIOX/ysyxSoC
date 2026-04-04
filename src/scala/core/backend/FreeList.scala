package ysyx.core.backend

import chisel3._
import chisel3.util._

import ysyx.core.common._

class FreeList extends NPCModule {
  private val numEntries = NRPhyReg // FIFO buffer size (stores preg numbers)
  private val idxBits = log2Ceil(numEntries)

  val io = IO(new Bundle {
    val alloc = Decoupled(UInt(NRPhyRegBits.W))
    val free = Flipped(Valid(UInt(NRPhyRegBits.W)))
    val commit_alloc = Input(Bool()) // signal that one allocation is now committed
    val flush = Input(Bool())
  })

  // Circular FIFO storing physical register numbers
  val ram = RegInit(VecInit((0 until numEntries).map(i =>
    if (i < NRPhyReg - NRReg) (i + NRReg).U(NRPhyRegBits.W)
    else 0.U(NRPhyRegBits.W)
  )))

  // p0..p31 initially mapped to x0..x31, so p32..p63 are free
  private val initCount = NRPhyReg - NRReg // 32

  val alloc_ptr = RegInit(0.U(idxBits.W))
  val free_ptr = RegInit(initCount.U(idxBits.W))
  val committed_alloc_ptr = RegInit(0.U(idxBits.W))

  val count = RegInit(initCount.U((idxBits + 1).W))
  val empty = count === 0.U

  io.alloc.valid := !empty && !io.flush
  io.alloc.bits := ram(alloc_ptr)

  val do_alloc = io.alloc.fire

  // commit_alloc and free must be processed even during flush,
  // because the flush-causing instruction (e.g. mispredicted JALR)
  // may also commit a register write in the same cycle.
  when(io.commit_alloc) {
    committed_alloc_ptr := committed_alloc_ptr + 1.U
  }
  when(io.free.valid) {
    ram(free_ptr) := io.free.bits
    free_ptr := free_ptr + 1.U
  }

  when(io.flush) {
    val new_committed = Mux(io.commit_alloc, committed_alloc_ptr + 1.U, committed_alloc_ptr)
    val new_free_ptr  = Mux(io.free.valid, free_ptr + 1.U, free_ptr)
    alloc_ptr := new_committed
    count := Mux(
      new_free_ptr >= new_committed,
      new_free_ptr - new_committed,
      numEntries.U - new_committed + new_free_ptr
    )
  }.otherwise {
    when(do_alloc) {
      alloc_ptr := alloc_ptr + 1.U
    }
    when(do_alloc && !io.free.valid) {
      count := count - 1.U
    }.elsewhen(!do_alloc && io.free.valid) {
      count := count + 1.U
    }
  }
}
