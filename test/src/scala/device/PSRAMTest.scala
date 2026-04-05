package ysyx.device.test

import chisel3._
import chisel3.util._
import chisel3.simulator.EphemeralSimulator._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import ysyx.soc.QSPIClgen
import ysyx.soc.QSPIShift

class QSPIClgenTest extends AnyFlatSpec with Matchers {
  behavior of "QSPIClgen"

  it should "generate edges with divider=1" in {
    simulate(new QSPIClgen(8)) { dut =>
      dut.reset.poke(true.B)
      dut.clock.step()
      dut.reset.poke(false.B)

      dut.io.go.poke(false.B)
      dut.io.tip.poke(false.B)
      dut.io.lastClk.poke(false.B)
      dut.io.divider.poke(1.U)
      dut.clock.step(2)

      dut.io.tip.poke(true.B)
      dut.io.go.poke(true.B)

      var posCount = 0
      var negCount = 0
      for (_ <- 0 until 20) {
        dut.clock.step()
        if (dut.io.posEdge.peek().litToBoolean) posCount += 1
        if (dut.io.negEdge.peek().litToBoolean) negCount += 1
      }

      posCount should be > 0
      negCount should be > 0
    }
  }

  it should "stay idle when not tipping" in {
    simulate(new QSPIClgen(8)) { dut =>
      dut.reset.poke(true.B)
      dut.clock.step()
      dut.reset.poke(false.B)

      dut.io.go.poke(false.B)
      dut.io.tip.poke(false.B)
      dut.io.lastClk.poke(false.B)
      dut.io.divider.poke(1.U)

      for (_ <- 0 until 10) {
        dut.clock.step()
        dut.io.clkOut.expect(false.B)
      }
    }
  }
}

class QSPIShiftTest extends AnyFlatSpec with Matchers {
  behavior of "QSPIShift"

  private def initAndReset(dut: QSPIShift): Unit = {
    dut.reset.poke(true.B)
    dut.io.go.poke(false.B)
    dut.io.wen.poke(false.B)
    dut.io.posEdge.poke(false.B)
    dut.io.negEdge.poke(false.B)
    dut.io.sIn.poke(0.U)
    dut.io.sClk.poke(false.B)
    dut.io.len4.poke(0.U)
    dut.io.sOutLen.poke(0.U)
    dut.io.pIn.poke(0.U)
    dut.clock.step()
    dut.reset.poke(false.B)
    dut.clock.step()
  }

  it should "be idle after reset" in {
    simulate(new QSPIShift(128)) { dut =>
      initAndReset(dut)
      dut.io.tip.expect(false.B)
    }
  }

  it should "load data via write-enable and transition to mosi on go" in {
    simulate(new QSPIShift(128)) { dut =>
      initAndReset(dut)

      dut.io.pIn.poke(0xABCD.U)
      dut.io.len4.poke(4.U)
      dut.io.sOutLen.poke(4.U)
      dut.io.wen.poke(true.B)
      dut.clock.step()
      dut.io.wen.poke(false.B)

      dut.io.tip.expect(false.B)

      dut.io.go.poke(true.B)
      dut.clock.step()
      dut.io.go.poke(false.B)

      dut.io.tip.expect(true.B)
      dut.io.sOutEn.expect(true.B)
    }
  }

  it should "complete transfer and return to idle" in {
    simulate(new QSPIShift(128)) { dut =>
      initAndReset(dut)

      dut.io.pIn.poke(0xFF.U)
      dut.io.len4.poke(2.U)
      dut.io.sOutLen.poke(2.U)
      dut.io.wen.poke(true.B)
      dut.clock.step()
      dut.io.wen.poke(false.B)

      dut.io.go.poke(true.B)
      dut.clock.step()
      dut.io.go.poke(false.B)

      dut.io.tip.expect(true.B)

      // Drive posEdge pulses to decrement counter to 0
      for (_ <- 0 until 5) {
        dut.io.posEdge.poke(true.B)
        dut.clock.step()
        dut.io.posEdge.poke(false.B)
        dut.clock.step()
      }

      dut.io.tip.expect(false.B)
    }
  }
}
