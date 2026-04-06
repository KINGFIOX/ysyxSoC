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

}
