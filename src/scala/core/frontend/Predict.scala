package ysyx.core.frontend

import chisel3._
import chisel3.util._
import ysyx.core.common._
import ysyx.core.backend.InstType
import ysyx.core.backend.CU

class Predict extends NPCModule {

  val io = IO(new Bundle {
    val predict = new PredictBundle
    val redirect = Input(Valid(new RedirectBundle)) // update
  })

  io.predict.dnpc := io.predict.pc + 4.U

}
