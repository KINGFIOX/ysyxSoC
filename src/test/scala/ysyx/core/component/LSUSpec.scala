package ysyx.core

import chisel3._
import chisel3.simulator.scalatest._

import org.scalatest.funspec.AnyFunSpec
import org.scalatest.matchers.must.Matchers
import freechips.rocketchip.amba.axi4._

import ysyx.core.lsu._

class LoadUnitSpec extends AnyFunSpec with Matchers with ChiselSim {
  val axiParams = AXI4BundleParameters(addrBits = 32, dataBits = 32, idBits = 4)

  private def initLoadUnit(dut: LoadUnit): Unit = {
    dut.in.req.poke(false.B)
    dut.in.bits.addr.poke(0.U)
    dut.in.bits.size.poke(0.U)
    dut.ar.ready.poke(false.B)
    dut.r.valid.poke(false.B)
    dut.r.bits.id.poke(0.U)
    dut.r.bits.data.poke(0.U)
    dut.r.bits.resp.poke(0.U)
    dut.r.bits.last.poke(true.B)
  }

  describe("LoadUnit") {

    it("should stay idle when no request") {
      simulate(new LoadUnit(axiParams, 0)) { dut =>
        initLoadUnit(dut)
        dut.ar.valid.expect(false.B)
        dut.r.ready.expect(false.B)
        dut.in.ack.expect(false.B)
        dut.in.done.expect(false.B)
      }
    }

    it("should complete a read when AR is immediately ready") {
      simulate(new LoadUnit(axiParams, 0)) { dut =>
        initLoadUnit(dut)

        // idle → req + ar.ready ⇒ ar.fire
        dut.in.req.poke(true.B)
        dut.in.bits.addr.poke(0x80001000L.U)
        dut.in.bits.size.poke(2.U)
        dut.ar.ready.poke(true.B)

        dut.ar.valid.expect(true.B)
        dut.in.ack.expect(true.B)
        dut.ar.bits.addr.expect(0x80001000L.U)
        dut.ar.bits.size.expect(2.U)
        dut.ar.bits.id.expect(0.U)
        dut.ar.bits.len.expect(0.U)
        dut.ar.bits.burst.expect(1.U)

        dut.clock.step() // stateQ → r_wait

        // r_wait → r responds
        dut.in.req.poke(false.B)
        dut.ar.ready.poke(false.B)
        dut.r.valid.poke(true.B)
        dut.r.bits.data.poke(0xDEADBEEFL.U)
        dut.r.bits.resp.poke(0.U)

        dut.ar.valid.expect(false.B)
        dut.r.ready.expect(true.B)
        dut.in.done.expect(true.B)
        dut.in.bits.rdata.expect(0xDEADBEEFL.U)
        dut.in.bits.resp.expect(0.U)

        dut.clock.step() // stateQ → idle

        dut.r.valid.poke(false.B)
        dut.in.req.poke(false.B)
        dut.ar.valid.expect(false.B)
        dut.r.ready.expect(false.B)
      }
    }

    it("should wait in ar_wait when AR is not ready") {
      simulate(new LoadUnit(axiParams, 0)) { dut =>
        initLoadUnit(dut)

        // idle → req, ar not ready ⇒ ar_wait
        dut.in.req.poke(true.B)
        dut.in.bits.addr.poke(0x2000.U)
        dut.in.bits.size.poke(1.U)

        dut.ar.valid.expect(true.B)
        dut.in.ack.expect(false.B)

        dut.clock.step() // stateQ → ar_wait

        // ar_wait → ar becomes ready
        dut.ar.ready.poke(true.B)

        dut.ar.valid.expect(true.B)
        dut.in.ack.expect(true.B)

        dut.clock.step() // stateQ → r_wait

        // r_wait → r response
        dut.ar.ready.poke(false.B)
        dut.r.valid.poke(true.B)
        dut.r.bits.data.poke(0x12345678.U)
        dut.r.bits.resp.poke(0.U)

        dut.r.ready.expect(true.B)
        dut.in.done.expect(true.B)
        dut.in.bits.rdata.expect(0x12345678.U)

        dut.clock.step() // stateQ → idle
      }
    }

    it("should hold rdata after read completes (holdUnless)") {
      simulate(new LoadUnit(axiParams, 0)) { dut =>
        initLoadUnit(dut)

        // do a read
        dut.in.req.poke(true.B)
        dut.in.bits.addr.poke(0x1000.U)
        dut.in.bits.size.poke(2.U)
        dut.ar.ready.poke(true.B)
        dut.clock.step() // → r_wait

        dut.in.req.poke(false.B)
        dut.ar.ready.poke(false.B)
        dut.r.valid.poke(true.B)
        dut.r.bits.data.poke(0xCAFEBABEL.U)
        dut.in.bits.rdata.expect(0xCAFEBABEL.U)
        dut.clock.step() // → idle, register captures 0xCAFEBABE

        // r.fire is now false, rdata should hold
        dut.r.valid.poke(false.B)
        dut.r.bits.data.poke(0.U)
        dut.in.bits.rdata.expect(0xCAFEBABEL.U)
      }
    }

    it("should pass correct ID from constructor parameter") {
      simulate(new LoadUnit(axiParams, 5)) { dut =>
        initLoadUnit(dut)
        dut.in.req.poke(true.B)
        dut.in.bits.addr.poke(0x100.U)
        dut.in.bits.size.poke(2.U)
        dut.ar.ready.poke(true.B)

        dut.ar.bits.id.expect(5.U)
      }
    }
  }
}

class StoreUnitSpec extends AnyFunSpec with Matchers with ChiselSim {
  val axiParams = AXI4BundleParameters(addrBits = 32, dataBits = 32, idBits = 4)

  private def initStoreUnit(dut: StoreUnit): Unit = {
    dut.in.req.poke(false.B)
    dut.in.bits.addr.poke(0.U)
    dut.in.bits.size.poke(0.U)
    dut.in.bits.wdata.poke(0.U)
    dut.in.bits.wstrb.poke(0.U)
    dut.aw.ready.poke(false.B)
    dut.w.ready.poke(false.B)
    dut.b.valid.poke(false.B)
    dut.b.bits.id.poke(0.U)
    dut.b.bits.resp.poke(0.U)
  }

  describe("StoreUnit") {

    it("should stay idle when no request") {
      simulate(new StoreUnit(axiParams, 1)) { dut =>
        initStoreUnit(dut)
        dut.aw.valid.expect(false.B)
        dut.w.valid.expect(false.B)
        dut.b.ready.expect(false.B)
        dut.in.ack.expect(false.B)
        dut.in.done.expect(false.B)
      }
    }

    it("should complete a write when AW and W fire simultaneously") {
      simulate(new StoreUnit(axiParams, 1)) { dut =>
        initStoreUnit(dut)

        // idle → both aw and w ready
        dut.in.req.poke(true.B)
        dut.in.bits.addr.poke(0x80002000L.U)
        dut.in.bits.size.poke(2.U)
        dut.in.bits.wdata.poke(0xAABBCCDDL.U)
        dut.in.bits.wstrb.poke(0xF.U)
        dut.aw.ready.poke(true.B)
        dut.w.ready.poke(true.B)

        dut.aw.valid.expect(true.B)
        dut.w.valid.expect(true.B)
        dut.aw.bits.addr.expect(0x80002000L.U)
        dut.aw.bits.size.expect(2.U)
        dut.aw.bits.id.expect(1.U)
        dut.aw.bits.len.expect(0.U)
        dut.aw.bits.burst.expect(1.U)
        dut.w.bits.data.expect(0xAABBCCDDL.U)
        dut.w.bits.strb.expect(0xF.U)
        dut.w.bits.last.expect(true.B)
        dut.in.ack.expect(true.B)

        dut.clock.step() // → b_wait

        // b_wait → b responds
        dut.in.req.poke(false.B)
        dut.aw.ready.poke(false.B)
        dut.w.ready.poke(false.B)
        dut.b.valid.poke(true.B)
        dut.b.bits.resp.poke(0.U)

        dut.b.ready.expect(true.B)
        dut.in.done.expect(true.B)
        dut.in.bits.resp.expect(0.U)

        dut.clock.step() // → idle

        dut.b.valid.poke(false.B)
        dut.in.req.poke(false.B)
        dut.clock.step() // let aw_sent_q/w_sent_q reset

        dut.aw.valid.expect(false.B)
        dut.w.valid.expect(false.B)
        dut.b.ready.expect(false.B)
      }
    }

    it("should handle AW fires before W") {
      simulate(new StoreUnit(axiParams, 1)) { dut =>
        initStoreUnit(dut)

        // idle → only AW ready
        dut.in.req.poke(true.B)
        dut.in.bits.addr.poke(0x3000.U)
        dut.in.bits.size.poke(2.U)
        dut.in.bits.wdata.poke(0x11223344.U)
        dut.in.bits.wstrb.poke(0xF.U)
        dut.aw.ready.poke(true.B)
        dut.w.ready.poke(false.B)

        dut.aw.valid.expect(true.B)
        dut.w.valid.expect(true.B)
        dut.in.ack.expect(false.B) // w not done

        dut.clock.step() // → aw_w_wait, aw_sent_q = true

        // aw_w_wait → W becomes ready
        dut.aw.ready.poke(false.B)
        dut.w.ready.poke(true.B)

        dut.aw.valid.expect(false.B) // aw_sent_q = true
        dut.w.valid.expect(true.B)
        dut.in.ack.expect(true.B) // aw_done && w_done

        dut.clock.step() // → b_wait

        // b responds
        dut.in.req.poke(false.B)
        dut.w.ready.poke(false.B)
        dut.b.valid.poke(true.B)
        dut.b.bits.resp.poke(0.U)

        dut.b.ready.expect(true.B)
        dut.in.done.expect(true.B)

        dut.clock.step() // → idle
      }
    }

    it("should handle W fires before AW") {
      simulate(new StoreUnit(axiParams, 1)) { dut =>
        initStoreUnit(dut)

        // idle → only W ready
        dut.in.req.poke(true.B)
        dut.in.bits.addr.poke(0x4000.U)
        dut.in.bits.size.poke(0.U) // 1 byte
        dut.in.bits.wdata.poke(0xFF.U)
        dut.in.bits.wstrb.poke(0x1.U)
        dut.aw.ready.poke(false.B)
        dut.w.ready.poke(true.B)

        dut.aw.valid.expect(true.B)
        dut.w.valid.expect(true.B)
        dut.in.ack.expect(false.B) // aw not done

        dut.clock.step() // → aw_w_wait, w_sent_q = true

        // aw_w_wait → AW becomes ready
        dut.aw.ready.poke(true.B)
        dut.w.ready.poke(false.B)

        dut.aw.valid.expect(true.B) // aw_sent_q = false
        dut.w.valid.expect(false.B) // w_sent_q = true
        dut.in.ack.expect(true.B) // aw_done && w_done

        dut.clock.step() // → b_wait

        // b responds
        dut.in.req.poke(false.B)
        dut.aw.ready.poke(false.B)
        dut.b.valid.poke(true.B)
        dut.b.bits.resp.poke(0.U)

        dut.b.ready.expect(true.B)
        dut.in.done.expect(true.B)

        dut.clock.step() // → idle
      }
    }

    it("should handle neither AW nor W ready initially") {
      simulate(new StoreUnit(axiParams, 1)) { dut =>
        initStoreUnit(dut)

        // idle → neither ready
        dut.in.req.poke(true.B)
        dut.in.bits.addr.poke(0x5000.U)
        dut.in.bits.size.poke(2.U)
        dut.in.bits.wdata.poke(0xDEADC0DEL.U)
        dut.in.bits.wstrb.poke(0xF.U)

        dut.aw.valid.expect(true.B)
        dut.w.valid.expect(true.B)
        dut.in.ack.expect(false.B)

        dut.clock.step() // → aw_w_wait

        // aw_w_wait: AW ready first
        dut.aw.ready.poke(true.B)
        dut.in.ack.expect(false.B) // w still not done

        dut.clock.step() // aw_sent_q = true, still aw_w_wait

        // aw_w_wait: W ready
        dut.aw.ready.poke(false.B)
        dut.w.ready.poke(true.B)
        dut.in.ack.expect(true.B) // both done

        dut.clock.step() // → b_wait

        // b responds
        dut.in.req.poke(false.B)
        dut.w.ready.poke(false.B)
        dut.b.valid.poke(true.B)
        dut.b.bits.resp.poke(0.U)

        dut.b.ready.expect(true.B)
        dut.in.done.expect(true.B)

        dut.clock.step() // → idle
      }
    }

    it("should pass correct ID from constructor parameter") {
      simulate(new StoreUnit(axiParams, 3)) { dut =>
        initStoreUnit(dut)
        dut.in.req.poke(true.B)
        dut.in.bits.addr.poke(0x100.U)
        dut.in.bits.size.poke(2.U)
        dut.in.bits.wdata.poke(0.U)
        dut.in.bits.wstrb.poke(0xF.U)
        dut.aw.ready.poke(true.B)
        dut.w.ready.poke(true.B)

        dut.aw.bits.id.expect(3.U)
      }
    }

    it("should output rdata as 0 (hardcoded)") {
      simulate(new StoreUnit(axiParams, 1)) { dut =>
        initStoreUnit(dut)
        dut.in.bits.rdata.expect(0.U)
      }
    }
  }
}
