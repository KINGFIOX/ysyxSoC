package ysyx.core.common

import chisel3._
import chisel3.util._
import ysyx.core.backend.RenameStageOutput
import ysyx.core.backend.CDBBundle

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

  // specialize for RenameStageOutput
  def apply(
      prevOut: DecoupledIO[RenameStageOutput],
      thisIn: DecoupledIO[RenameStageOutput],
      flush: Bool,
      cdbs: Seq[ValidIO[CDBBundle]]
  ): Unit = {
    val pipe_valid = RegInit(false.B)
    val pipe_bits = Reg(new RenameStageOutput)

    when(pipe_valid && !thisIn.ready && !flush) {
      for (i <- 0 until 2) {
        when(!pipe_bits.src(i).ready) {
          cdbs.reverse.foreach { cdb =>
            when(cdb.valid && pipe_bits.src(i).tag === cdb.bits.tag) {
              pipe_bits.src(i).ready := true.B
              pipe_bits.src(i).value := cdb.bits.value
            }
          }
        }
      }
    }

    when(flush) {
      pipe_valid := false.B
    }.elsewhen(thisIn.ready) {
      pipe_valid := prevOut.valid
      when(prevOut.valid) { // catch when fire
        pipe_bits := prevOut.bits
      }
    }
    prevOut.ready := thisIn.ready
    thisIn.valid := pipe_valid
    thisIn.bits := pipe_bits
  }

}
