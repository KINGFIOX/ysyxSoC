// APB N-to-1 Arbiter
// Multiple masters to single slave with priority-based arbitration

package ysyx

import chisel3._
import chisel3.util._

import org.chipsalliance.cde.config.Parameters
import org.chipsalliance.diplomacy.lazymodule.{LazyModule, LazyModuleImp}

import freechips.rocketchip.amba.apb._
import freechips.rocketchip.util.BundleField

/** APB N-to-1 Arbiter
  *
  * Arbitrates between multiple APB masters to access a single APB slave.
  * Uses priority-based arbitration where lower index has higher priority.
  *
  * This is the inverse of APBFanout (which is 1-to-N).
  *
  * Architecture:
  *   Master0 (highest priority) ──┐
  *   Master1                    ──┼── Arbiter ──> Slave
  *   Master2 (lowest priority)  ──┘
  */
class APBArbiter()(implicit p: Parameters) extends LazyModule {
  val node = new APBNexusNode(
    // Combine multiple masters into one output master
    masterFn = { seq =>
      seq.head.copy(
        masters = seq.flatMap(_.masters),
        requestFields = BundleField.union(seq.flatMap(_.requestFields))
      )
    },
    // Expose the same slave to all masters
    slaveFn = { case Seq(s) => s }
  ) {
    override def circuitIdentity = outputs.size == 1 && inputs.size == 1
  }

  lazy val module = new LazyModuleImp(this) {
    if (node.edges.out.size >= 1) {
      require(node.edges.out.size == 1, "APBArbiter supports only one slave")
      require(node.edges.in.size > 0, "APBArbiter requires at least one master")

      val (out, _) = node.out(0)
      val (io_in, edgesIn) = node.in.unzip

      // Require consistent bus widths
      val port0 = edgesIn(0).slave
      edgesIn.foreach { edge =>
        val port = edge.slave
        require(
          port.beatBytes == port0.beatBytes,
          s"APBArbiter: inconsistent beatBytes ${port.beatBytes} vs ${port0.beatBytes}"
        )
      }

      // Priority arbitration: lower index = higher priority
      // Master is active when psel is asserted
      val masterActive = io_in.map(_.psel)

      // One-hot selection: select the highest priority active master
      val sel = Wire(Vec(io_in.size, Bool()))
      sel(0) := masterActive(0)
      for (i <- 1 until io_in.size) {
        sel(i) := masterActive(i) && !masterActive.take(i).reduce(_ || _)
      }

      // Forward selected master to slave
      out.psel := sel.reduce(_ || _)
      out.penable := Mux1H(sel, io_in.map(_.penable))
      out.paddr := Mux1H(sel, io_in.map(_.paddr))
      out.pwrite := Mux1H(sel, io_in.map(_.pwrite))
      out.pwdata := Mux1H(sel, io_in.map(_.pwdata))
      out.pstrb := Mux1H(sel, io_in.map(_.pstrb))
      out.pprot := Mux1H(sel, io_in.map(_.pprot))

      // Distribute slave responses
      (sel zip io_in).foreach { case (selected, in) =>
        in.pready := selected && out.pready
        in.prdata := out.prdata
        in.pslverr := selected && out.pslverr
      }
    }
  }
}
