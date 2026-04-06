package ysyx.core.common

import chisel3._
import chisel3.util._
import ysyx.core.backend.RenameStageOutput
import ysyx.core.backend.WakeupPort

object PipelineConnect {

  def apply[T <: Data](
      prevOut: DecoupledIO[T],
      thisIn: DecoupledIO[T],
      flush: Bool
  ): Unit = {
    val valid = RegInit(false.B)
    when(flush) {
      valid := false.B
    }.elsewhen(thisIn.ready) {
      valid := prevOut.valid
    }
    prevOut.ready := thisIn.ready
    thisIn.bits := RegEnable(prevOut.bits, prevOut.valid && thisIn.ready)
    thisIn.valid := valid
  }

  // Specialized for RenameStageOutput: update ready bits via wakeup in pipe register
  def apply(
      prevOut: DecoupledIO[RenameStageOutput],
      thisIn: DecoupledIO[RenameStageOutput],
      flush: Bool,
      wakeups: Seq[ValidIO[WakeupPort]]
  ): Unit = {
    val pipe_valid = RegInit(false.B)
    val pipe_bits = Reg(new RenameStageOutput)

    when(pipe_valid && !thisIn.ready && !flush) {
      for (wk <- wakeups) {
        when(wk.valid) {
          when(!pipe_bits.prs1_ready && pipe_bits.prs1 === wk.bits.prd) {
            pipe_bits.prs1_ready := true.B
          }
          when(!pipe_bits.prs2_ready && pipe_bits.prs2 === wk.bits.prd) {
            pipe_bits.prs2_ready := true.B
          }
        }
      }
    }

    when(flush) {
      pipe_valid := false.B
    }.elsewhen(thisIn.ready) {
      pipe_valid := prevOut.valid
      when(prevOut.valid) {
        pipe_bits := prevOut.bits
      }
    }
    prevOut.ready := thisIn.ready
    thisIn.valid := pipe_valid
    thisIn.bits := pipe_bits
  }

}
