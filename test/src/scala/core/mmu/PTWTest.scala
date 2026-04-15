package ysyx.core.mmu.test

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import ysyx.core.mmu.PTW
import ysyx.testkit.SimHelpers._

class PTWTest extends AnyFlatSpec with Matchers {
  behavior of "PTW"

  private def satpSv39(ppn: BigInt): BigInt = (BigInt(8) << 60) | ppn

  private def setupDefaults(dut: PTW): Unit = {
    dut.reset.poke(true.B)
    dut.io.req.valid.poke(false.B)
    dut.io.req.bits.vpn.poke(0.U)
    dut.io.satp.poke(0.U)
    dut.io.priv.poke(3.U)
    dut.io.mem.ack.poke(false.B)
    dut.io.mem.done.poke(false.B)
    dut.io.mem.rdata.poke(0.U)
    dut.clock.step(2)
    dut.reset.poke(false.B)
    dut.clock.step()
  }

  private def doMemRead(dut: PTW, expectAddr: BigInt, pteValue: BigInt): Unit = {
    stepUntil(
      dut.io.mem.req.peek().litToBoolean,
      maxCycles = 20,
      clue = s"PTW mem.req at addr 0x${expectAddr.toString(16)}"
    ) { dut.clock.step() }
    dut.io.mem.addr.expect(expectAddr.U)
    dut.io.mem.ack.poke(true.B)
    dut.clock.step()
    dut.io.mem.ack.poke(false.B)

    dut.io.mem.done.poke(true.B)
    dut.io.mem.rdata.poke(pteValue.U)
    dut.clock.step()
    dut.io.mem.done.poke(false.B)
  }

  it should "walk three levels and return ppn/flags on success" in {
    simulate(new PTW) { dut =>
      setupDefaults(dut)

      val rootPpn = BigInt("100", 16)
      val vpn2 = BigInt("12", 16)
      val vpn1 = BigInt("34", 16)
      val vpn0 = BigInt("56", 16)
      val vpn = (vpn2 << 18) | (vpn1 << 9) | vpn0
      val l2Ppn = BigInt("12345", 16)
      val l1Ppn = BigInt("54321", 16)
      val leafPpn = BigInt("abcde", 16)
      val leafFlags = 0xd7 // V/R/W/X/U/A/D mixture

      dut.io.satp.poke(satpSv39(rootPpn).U)
      dut.io.req.bits.vpn.poke(vpn.U)
      dut.io.req.valid.poke(true.B)
      dut.clock.step()
      dut.io.req.valid.poke(false.B)

      val l2Addr = (rootPpn << 12) + (vpn2 << 3)
      val l1Addr = (l2Ppn << 12) + (vpn1 << 3)
      val l0Addr = (l1Ppn << 12) + (vpn0 << 3)

      doMemRead(dut, l2Addr, pte(ppn = l2Ppn, flags = 0x01)) // valid non-leaf
      doMemRead(dut, l1Addr, pte(ppn = l1Ppn, flags = 0x01)) // valid non-leaf
      doMemRead(dut, l0Addr, pte(ppn = leafPpn, flags = leafFlags))

      stepUntil(dut.io.resp.valid.peek().litToBoolean, 20, "resp.valid on success") { dut.clock.step() }
      dut.io.resp.bits.fault.expect(false.B)
      dut.io.resp.bits.ppn.expect(leafPpn.U)
      dut.io.resp.bits.flags.expect(leafFlags.U)
    }
  }

  it should "raise fault on invalid level2 PTE" in {
    simulate(new PTW) { dut =>
      setupDefaults(dut)

      val rootPpn = BigInt("200", 16)
      val vpn = BigInt("12345", 16)
      val vpn2 = (vpn >> 18) & 0x1ff
      val l2Addr = (rootPpn << 12) + (vpn2 << 3)

      dut.io.satp.poke(satpSv39(rootPpn).U)
      dut.io.req.bits.vpn.poke(vpn.U)
      dut.io.req.valid.poke(true.B)
      dut.clock.step()
      dut.io.req.valid.poke(false.B)

      doMemRead(dut, l2Addr, pte(ppn = BigInt("dead", 16), flags = 0x00)) // V=0

      stepUntil(dut.io.resp.valid.peek().litToBoolean, 20, "resp.valid on fault") { dut.clock.step() }
      dut.io.resp.bits.fault.expect(true.B)
    }
  }

  it should "raise fault on invalid leaf permissions (R=0,W=1)" in {
    simulate(new PTW) { dut =>
      setupDefaults(dut)

      val rootPpn = BigInt("300", 16)
      val vpn2 = BigInt("1a", 16)
      val vpn1 = BigInt("0b", 16)
      val vpn0 = BigInt("02", 16)
      val vpn = (vpn2 << 18) | (vpn1 << 9) | vpn0
      val l2Ppn = BigInt("11111", 16)
      val l1Ppn = BigInt("22222", 16)

      dut.io.satp.poke(satpSv39(rootPpn).U)
      dut.io.req.bits.vpn.poke(vpn.U)
      dut.io.req.valid.poke(true.B)
      dut.clock.step()
      dut.io.req.valid.poke(false.B)

      val l2Addr = (rootPpn << 12) + (vpn2 << 3)
      val l1Addr = (l2Ppn << 12) + (vpn1 << 3)
      val l0Addr = (l1Ppn << 12) + (vpn0 << 3)

      doMemRead(dut, l2Addr, pte(ppn = l2Ppn, flags = 0x01))
      doMemRead(dut, l1Addr, pte(ppn = l1Ppn, flags = 0x01))
      doMemRead(dut, l0Addr, pte(ppn = BigInt("33333", 16), flags = 0x05)) // V=1,R=0,W=1

      stepUntil(dut.io.resp.valid.peek().litToBoolean, 20, "resp.valid on leaf fault") { dut.clock.step() }
      dut.io.resp.bits.fault.expect(true.B)
    }
  }
}
