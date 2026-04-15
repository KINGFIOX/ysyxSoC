package ysyx.cpu.cache.test

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import ysyx.core.common.{HasAXIParameter, HasSRAMParameter}
import ysyx.cpu.cache.DCacheImpl
import ysyx.testkit.SimHelpers._

object DCacheTestParams extends HasSRAMParameter with HasAXIParameter

class DCacheHarness extends Module {
  val io = IO(new Bundle {
    val req = Input(Bool())
    val wen = Input(Bool())
    val size = Input(UInt(3.W))
    val addr = Input(UInt(64.W))
    val wstrb = Input(UInt(8.W))
    val wdata = Input(UInt(64.W))
    val fence_i = Input(Bool())
    val sfence_vma = Input(Bool())

    val ack = Output(Bool())
    val done = Output(Bool())
    val rdata = Output(UInt(64.W))

    val arValid = Output(Bool())
    val arAddr = Output(UInt(64.W))
    val arReady = Input(Bool())
    val awValid = Output(Bool())
    val awAddr = Output(UInt(64.W))
    val awReady = Input(Bool())
    val wValid = Output(Bool())
    val wData = Output(UInt(64.W))
    val wLast = Output(Bool())
    val wReady = Input(Bool())
    val bReady = Output(Bool())
    val bValid = Input(Bool())

    val rValid = Input(Bool())
    val rData = Input(UInt(64.W))
    val rLast = Input(Bool())
  })

  val cache = Module(new DCacheImpl(0, DCacheTestParams.sramParams, DCacheTestParams.axiParams))
  cache.fence_i := io.fence_i
  cache.sfence_vma := io.sfence_vma

  cache.io.in.req := io.req
  cache.io.in.wen := io.wen
  cache.io.in.size := io.size
  cache.io.in.addr := io.addr
  cache.io.in.wstrb := io.wstrb
  cache.io.in.wdata := io.wdata
  io.ack := cache.io.in.ack
  io.done := cache.io.in.done
  io.rdata := cache.io.in.rdata

  io.arValid := cache.io.out.ar.valid
  io.arAddr := cache.io.out.ar.bits.addr
  cache.io.out.ar.ready := io.arReady

  io.awValid := cache.io.out.aw.valid
  io.awAddr := cache.io.out.aw.bits.addr
  cache.io.out.aw.ready := io.awReady

  io.wValid := cache.io.out.w.valid
  io.wData := cache.io.out.w.bits.data
  io.wLast := cache.io.out.w.bits.last
  cache.io.out.w.ready := io.wReady

  io.bReady := cache.io.out.b.ready
  cache.io.out.b.valid := io.bValid
  cache.io.out.b.bits := 0.U.asTypeOf(cache.io.out.b.bits)

  val rBits = WireDefault(0.U.asTypeOf(cache.io.out.r.bits))
  rBits.data := io.rData
  rBits.last := io.rLast
  cache.io.out.r.valid := io.rValid
  cache.io.out.r.bits := rBits
}

class AXI4DCacheTest extends AnyFlatSpec with Matchers {
  behavior of "DCacheImpl"

  private def defaults(dut: DCacheHarness): Unit = {
    dut.reset.poke(true.B)
    dut.io.req.poke(false.B)
    dut.io.wen.poke(false.B)
    dut.io.size.poke(3.U)
    dut.io.addr.poke(0.U)
    dut.io.wstrb.poke(0.U)
    dut.io.wdata.poke(0.U)
    dut.io.fence_i.poke(false.B)
    dut.io.sfence_vma.poke(false.B)
    dut.io.arReady.poke(false.B)
    dut.io.awReady.poke(false.B)
    dut.io.wReady.poke(false.B)
    dut.io.bValid.poke(false.B)
    dut.io.rValid.poke(false.B)
    dut.io.rData.poke(0.U)
    dut.io.rLast.poke(false.B)
    dut.clock.step(2)
    dut.reset.poke(false.B)
    dut.clock.step()
  }

  private def issueReq(dut: DCacheHarness, addr: BigInt, wen: Boolean, wstrb: BigInt = 0, wdata: BigInt = 0): Unit = {
    dut.io.addr.poke(addr.U)
    dut.io.wen.poke(wen.B)
    dut.io.wstrb.poke(wstrb.U)
    dut.io.wdata.poke(wdata.U)
    dut.io.req.poke(true.B)
    dut.clock.step()
    dut.io.req.poke(false.B) // poke false.B but with no time consumse
  }

  private def acceptArAndRefill(dut: DCacheHarness, baseData: BigInt): Unit = {
    stepUntil(dut.io.arValid.peek().litToBoolean, 30, "AR valid on miss") { dut.clock.step() }
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

  it should "read miss-refill-hit correctly" in {
    simulate(new DCacheHarness) { dut =>
      defaults(dut)
      val addr = BigInt("18", 16) // target beat = 3
      issueReq(dut, addr, wen = false)
      acceptArAndRefill(dut, BigInt("300", 16))

      issueReq(dut, addr, wen = false)
      stepUntil(dut.io.done.peek().litToBoolean, 6, "done on cache hit") { dut.clock.step() }
      val hitData = dut.io.rdata.peek().litValue
      assert(hitData >= BigInt("300", 16) && hitData <= BigInt("307", 16), s"unexpected hit data: 0x${hitData.toString(16)}")
    }
  }

  it should "merge write data on write miss refill and read back merged result" in {
    simulate(new DCacheHarness) { dut =>
      defaults(dut)
      val addr = BigInt("28", 16) // target beat = 5
      val base = BigInt("1122334455667700", 16)
      val writeData = BigInt("00000000deadbeef", 16)
      val writeMask = BigInt("0f", 16) // lower 32 bits
      val expected = (base & BigInt("ffffffff00000000", 16)) | (writeData & BigInt("ffffffff", 16))

      issueReq(dut, addr, wen = true, wstrb = writeMask, wdata = writeData)
      acceptArAndRefill(dut, base)

      issueReq(dut, addr, wen = false)
      stepUntil(dut.io.done.peek().litToBoolean, 6, "read hit after write-allocate") { dut.clock.step() }
      dut.io.rdata.expect(expected.U)
    }
  }

  it should "complete flush and accept new requests afterwards" in {
    simulate(new DCacheHarness) { dut =>
      defaults(dut)
      dut.io.awReady.poke(true.B)
      dut.io.wReady.poke(true.B)
      dut.io.bValid.poke(true.B)
      dut.io.fence_i.poke(true.B)
      dut.clock.step()
      dut.io.fence_i.poke(false.B)

      // Flush scans 64 sets * 4 ways in worst case when all lines clean.
      // If random initial state contains dirty lines, flush may require writeback handshakes.
      stepN(2500) { dut.clock.step() }

      issueReq(dut, BigInt("100", 16), wen = false)
      stepUntil(dut.io.arValid.peek().litToBoolean, 20, "request accepted after flush") { dut.clock.step() }
    }
  }
}
