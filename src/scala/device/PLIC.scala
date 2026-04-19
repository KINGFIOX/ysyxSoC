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

  // Level-sensitive gateway: while source is high and the IRQ is not currently
  // being serviced (claimed), keep pending asserted. Once claimed, hold until
  // plic_complete clears `claimed`, then re-arm if source is still high.
  val sources_vec = io.sources.asUInt
  // claim_set/clear are driven by the access path below; defaults are 0
  val claim_set   = WireDefault(0.U(numSources.W))
  val claim_clear = WireDefault(0.U(numSources.W))
  // Use the in-flight claim (claim_set) to mask the gateway in the same cycle
  // so the just-claimed bit cannot be re-asserted before plic_complete runs.
  val claimed_eff = claimed | claim_set
  val gateway_set = sources_vec & ~claimed_eff
  pending := (pending & ~claim_clear) | gateway_set

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
              claim_clear := (1.U << best_irq)
              claim_set   := (1.U << best_irq)
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
