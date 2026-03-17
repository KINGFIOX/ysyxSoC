package ysyx.util

import chisel3._
import chisel3.util._

class ReqDoneIO[T <: Data](gen: => T) extends Bundle {
  val req = Output(Bool())
  val done = Input(Bool())
  val bits = gen
  override def typeName: String =
    s"${simpleClassName(this.getClass)}_${bits.typeName}"
}

class ReqAckDoneIO[T <: Data](gen: => T) extends Bundle {
  val req = Output(Bool())
  val ack = Input(Bool())
  val done = Input(Bool())
  val bits = gen
  override def typeName: String =
    s"${simpleClassName(this.getClass)}_${bits.typeName}"
}

object ReqDone {
  def apply[T <: Data](gen: T): ReqDoneIO[T] = new ReqDoneIO(gen)
}

object ReqAckDone {
  def apply[T <: Data](gen: T): ReqAckDoneIO[T] = new ReqAckDoneIO(gen)
}
