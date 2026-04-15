package ysyx.core.mmu.test

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import ysyx.core.mmu.TLB

class TLBTest extends AnyFlatSpec with Matchers {
  behavior of "TLB"

  private def driveLookup(dut: TLB, vpn: BigInt): Unit = {
    dut.io.lookup.req.vpn.poke(vpn.U)
    dut.clock.step()
  }

  it should "miss on reset then hit after refill" in {
    simulate(new TLB(numEntries = 2)) { dut =>
      dut.io.flush.poke(false.B)
      dut.io.refill.valid.poke(false.B)
      driveLookup(dut, 0x12)
      dut.io.lookup.resp.hit.expect(false.B)

      dut.io.refill.valid.poke(true.B)
      dut.io.refill.vpn.poke(0x12.U)
      dut.io.refill.ppn.poke(0x1234.U)
      dut.io.refill.flags.poke("hff".U)
      dut.clock.step()
      dut.io.refill.valid.poke(false.B)

      driveLookup(dut, 0x12)
      dut.io.lookup.resp.hit.expect(true.B)
      dut.io.lookup.resp.ppn.expect(0x1234.U)
      dut.io.lookup.resp.flags.expect("hff".U)
    }
  }

  it should "replace entries in FIFO pointer order" in {
    simulate(new TLB(numEntries = 2)) { dut =>
      dut.io.flush.poke(false.B)
      dut.io.refill.valid.poke(false.B)
      dut.clock.step()

      dut.io.refill.valid.poke(true.B)
      dut.io.refill.vpn.poke(1.U)
      dut.io.refill.ppn.poke(0x111.U)
      dut.io.refill.flags.poke(1.U)
      dut.clock.step()

      dut.io.refill.vpn.poke(2.U)
      dut.io.refill.ppn.poke(0x222.U)
      dut.io.refill.flags.poke(2.U)
      dut.clock.step()

      // Third refill should overwrite entry 0 (vpn = 1).
      dut.io.refill.vpn.poke(3.U)
      dut.io.refill.ppn.poke(0x333.U)
      dut.io.refill.flags.poke(3.U)
      dut.clock.step()
      dut.io.refill.valid.poke(false.B)

      driveLookup(dut, 1)
      dut.io.lookup.resp.hit.expect(false.B)

      driveLookup(dut, 2)
      dut.io.lookup.resp.hit.expect(true.B)
      dut.io.lookup.resp.ppn.expect(0x222.U)

      driveLookup(dut, 3)
      dut.io.lookup.resp.hit.expect(true.B)
      dut.io.lookup.resp.ppn.expect(0x333.U)
    }
  }

  it should "invalidate all entries on flush" in {
    simulate(new TLB(numEntries = 4)) { dut =>
      dut.io.flush.poke(false.B)
      dut.io.refill.valid.poke(true.B)
      dut.io.refill.vpn.poke(0x2a.U)
      dut.io.refill.ppn.poke(0x55aa.U)
      dut.io.refill.flags.poke("haa".U)
      dut.clock.step()
      dut.io.refill.valid.poke(false.B)

      driveLookup(dut, 0x2a)
      dut.io.lookup.resp.hit.expect(true.B)

      dut.io.flush.poke(true.B)
      dut.clock.step()
      dut.io.flush.poke(false.B)

      driveLookup(dut, 0x2a)
      dut.io.lookup.resp.hit.expect(false.B)
    }
  }
}
