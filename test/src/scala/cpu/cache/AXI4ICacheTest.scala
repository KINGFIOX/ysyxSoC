package ysyx.cpu.cache.test

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import ysyx.core.common.{HasAXIParameter, HasSRAMParameter}
import ysyx.cpu.cache.ICacheImpl
import ysyx.testkit.SimHelpers._

object ICacheTestParams extends HasSRAMParameter with HasAXIParameter

class ICacheHarness extends Module {
  val io = IO(new Bundle {
    val req = Input(Bool())
    val addr = Input(UInt(64.W))
    val fence_i = Input(Bool())

    val ack = Output(Bool())
    val done = Output(Bool())
    val rdata = Output(UInt(64.W))

    val arValid = Output(Bool())
    val arAddr = Output(UInt(64.W))
    val arReady = Input(Bool())

    val rValid = Input(Bool())
    val rData = Input(UInt(64.W))
    val rLast = Input(Bool())
  })

  val cache = Module(new ICacheImpl(0, ICacheTestParams.sramParams, ICacheTestParams.axiParams))
  cache.fence_i := io.fence_i

  cache.io.in.req := io.req
  cache.io.in.wen := false.B
  cache.io.in.size := 3.U
  cache.io.in.addr := io.addr
  cache.io.in.wstrb := 0.U
  cache.io.in.wdata := 0.U
  io.ack := cache.io.in.ack
  io.done := cache.io.in.done
  io.rdata := cache.io.in.rdata

  io.arValid := cache.io.out.ar.valid
  io.arAddr := cache.io.out.ar.bits.addr
  cache.io.out.ar.ready := io.arReady

  val rBits = WireDefault(0.U.asTypeOf(cache.io.out.r.bits))
  rBits.data := io.rData
  rBits.last := io.rLast
  cache.io.out.r.valid := io.rValid
  cache.io.out.r.bits := rBits

  cache.io.out.aw.ready := false.B
  cache.io.out.w.ready := false.B
  cache.io.out.b.valid := false.B
  cache.io.out.b.bits := 0.U.asTypeOf(cache.io.out.b.bits)
}

class AXI4ICacheTest extends AnyFlatSpec with Matchers {
  behavior of "ICacheImpl"

  private def defaults(dut: ICacheHarness): Unit = {
    dut.reset.poke(true.B)
    dut.io.req.poke(false.B)
    dut.io.addr.poke(0.U)
    dut.io.fence_i.poke(false.B)
    dut.io.arReady.poke(false.B)
    dut.io.rValid.poke(false.B)
    dut.io.rData.poke(0.U)
    dut.io.rLast.poke(false.B)
    dut.clock.step(2)
    dut.reset.poke(false.B)
    dut.clock.step()
  }

  private def issueRead(dut: ICacheHarness, addr: BigInt): Unit = {
    dut.io.addr.poke(addr.U)
    dut.io.req.poke(true.B)
    dut.clock.step()
    dut.io.req.poke(false.B)
  }

  private def refillLine(dut: ICacheHarness, baseData: BigInt): Unit = {
    stepUntil(dut.io.arValid.peek().litToBoolean, 20, "AR valid on miss") { dut.clock.step() }
    dut.io.arReady.poke(true.B)
    dut.clock.step()
    dut.io.arReady.poke(false.B)
    for (i <- 0 until 8) {
      dut.io.rValid.poke(true.B)
      dut.io.rData.poke((baseData + i).U)
      dut.io.rLast.poke((i == 7).B)
      dut.clock.step()
    }
    dut.io.rValid.poke(false.B)
    dut.io.rLast.poke(false.B)
  }

  it should "miss, refill, and then hit on the same address" in {
    simulate(new ICacheHarness) { dut =>
      defaults(dut)
      val addr = BigInt("10", 16) // target beat = 2
      issueRead(dut, addr)
      refillLine(dut, BigInt("100", 16))

      issueRead(dut, addr)
      stepUntil(dut.io.done.peek().litToBoolean, 5, "done on hit") { dut.clock.step() }
      dut.io.rdata.expect("h102".U)
    }
  }

  it should "invalidate cachelines on fence_i and trigger miss again" in {
    simulate(new ICacheHarness) { dut =>
      defaults(dut)
      val addr = BigInt("20", 16)

      issueRead(dut, addr)
      refillLine(dut, BigInt("200", 16))

      dut.io.fence_i.poke(true.B)
      dut.clock.step()
      dut.io.fence_i.poke(false.B)

      issueRead(dut, addr)
      stepUntil(dut.io.arValid.peek().litToBoolean, 20, "miss after fence_i") { dut.clock.step() }
    }
  }

  it should "trigger a new miss when switching to another cacheline" in {
    simulate(new ICacheHarness) { dut =>
      defaults(dut)
      val addrA = BigInt("00", 16)
      val addrB = BigInt("80", 16) // different tag/index domain than addrA

      issueRead(dut, addrA)
      refillLine(dut, BigInt("400", 16))

      issueRead(dut, addrA)
      stepUntil(dut.io.done.peek().litToBoolean, 6, "line A hit done") { dut.clock.step() }

      issueRead(dut, addrB)
      stepUntil(dut.io.arValid.peek().litToBoolean, 20, "line B miss") { dut.clock.step() }
      dut.io.arAddr.expect("h80".U)
    }
  }
}
