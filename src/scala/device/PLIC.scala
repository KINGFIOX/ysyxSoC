package ysyx.device

import chisel3._
import chisel3.util._

import freechips.rocketchip.amba.apb._
import org.chipsalliance.cde.config.Parameters
import freechips.rocketchip.diplomacy._

class plic_apb(numSources: Int = 32) extends Module {
  val io = IO(new Bundle {
    val in = Flipped(
      new APBBundle(APBBundleParameters(addrBits = 32, dataBits = 32))
    )
    val sources = Input(Vec(numSources, Bool()))
    val ext_irq = Output(Bool())
  })

  object State extends ChiselEnum {
    val idle, access = Value
  }
  private val stateQ = RegInit(State.idle)
  private val rdataQ = RegInit(0.U(32.W))

  io.in.prdata := rdataQ
  io.in.pslverr := false.B
  io.in.pready := stateQ === State.access

  // PLIC registers
  val priority = RegInit(VecInit(Seq.fill(numSources)(0.U(32.W))))
  val pending  = RegInit(0.U(numSources.W))
  val s_enable = RegInit(0.U(numSources.W))
  val s_threshold = RegInit(0.U(32.W))
  val claimed  = RegInit(0.U(numSources.W))

  // Edge detection: latch rising edges of source signals into pending
  val sources_prev = RegNext(io.sources)
  for (i <- 1 until numSources) {
    when(io.sources(i) && !sources_prev(i) && !claimed(i)) {
      pending := pending | (1.U << i.U)
    }
  }

  // Determine highest-priority pending & enabled interrupt for S-mode claim
  val claimable = pending & s_enable & ~claimed
  val (best_irq, _) = (1 until numSources).foldLeft(
    (0.U(log2Ceil(numSources + 1).W), 0.U(32.W))
  ) { case ((irq_acc, prio_acc), i) =>
    val take = claimable(i) && priority(i) > s_threshold && priority(i) > prio_acc
    (Mux(take, i.U, irq_acc), Mux(take, priority(i), prio_acc))
  }

  io.ext_irq := best_irq =/= 0.U

  private val paddr = io.in.paddr(21, 0)

  switch(stateQ) {
    is(State.idle) {
      when(io.in.psel) {
        stateQ := State.access

        when(io.in.pwrite) {
          // Write path
          when(paddr < (numSources * 4).U) {
            // Priority register: addr = IRQ * 4
            val src_idx = paddr(log2Ceil(numSources) + 1, 2)
            priority(src_idx) := io.in.pwdata
          }.elsewhen(paddr === "h002080".U) {
            // S-mode enable (hart 0)
            s_enable := io.in.pwdata(numSources - 1, 0)
          }.elsewhen(paddr === "h201000".U) {
            // S-mode threshold (hart 0)
            s_threshold := io.in.pwdata
          }.elsewhen(paddr === "h201004".U) {
            // S-mode complete (hart 0)
            val completed_irq = io.in.pwdata(log2Ceil(numSources) - 1, 0)
            claimed := claimed & ~(1.U << completed_irq)
          }
        }.otherwise {
          // Read path
          when(paddr < (numSources * 4).U) {
            val src_idx = paddr(log2Ceil(numSources) + 1, 2)
            rdataQ := priority(src_idx)
          }.elsewhen(paddr === "h001000".U) {
            rdataQ := pending(31, 0)
          }.elsewhen(paddr === "h002080".U) {
            rdataQ := s_enable(31, 0)
          }.elsewhen(paddr === "h201000".U) {
            rdataQ := s_threshold
          }.elsewhen(paddr === "h201004".U) {
            // S-mode claim (hart 0)
            rdataQ := best_irq
            when(best_irq =/= 0.U) {
              pending := pending & ~(1.U << best_irq)
              claimed := claimed | (1.U << best_irq)
            }
          }.otherwise {
            rdataQ := 0.U
          }
        }
      }
    }

    is(State.access) {
      when(io.in.penable) {
        stateQ := State.idle
      }
    }
  }
}

class APBPLIC(address: Seq[AddressSet], numSources: Int = 32)(implicit p: Parameters)
    extends LazyModule {
  val node = APBSlaveNode(
    Seq(
      APBSlavePortParameters(
        Seq(
          APBSlaveParameters(
            address = address,
            executable = false,
            supportsRead = true,
            supportsWrite = true
          )
        ),
        beatBytes = 4
      )
    )
  )

  lazy val module = new Impl
  class Impl extends LazyModuleImp(this) {
    val (in, _) = node.in(0)
    val sources = IO(Input(Vec(numSources, Bool())))
    val ext_irq = IO(Output(Bool()))
    val mplic = Module(new plic_apb(numSources))
    mplic.io.in <> in
    mplic.io.sources := sources
    ext_irq := mplic.io.ext_irq
  }
}
