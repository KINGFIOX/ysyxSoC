package ysyx.core.lsu.test

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import ysyx.core.lsu.LSU
import ysyx.testkit.SimHelpers._

class LSUHarness extends Module {
  val io = IO(new Bundle {
    val req = Input(Bool())
    val prs1 = Input(UInt(6.W))
    val prs2 = Input(UInt(6.W))
    val prs1Data = Input(UInt(64.W))
    val prs2Data = Input(UInt(64.W))
    val imm = Input(UInt(64.W))
    val size = Input(UInt(3.W))
    val signExt = Input(Bool())
    val rEn = Input(Bool())
    val wEn = Input(Bool())
    val satp = Input(UInt(64.W))
    val priv = Input(UInt(2.W))
    val sfenceVma = Input(Bool())

    val dAck = Input(Bool())
    val dDone = Input(Bool())
    val dRData = Input(UInt(64.W))
    val pAck = Input(Bool())
    val pDone = Input(Bool())
    val pRData = Input(UInt(64.W))
    val ptwAck = Input(Bool())
    val ptwDone = Input(Bool())
    val ptwRData = Input(UInt(64.W))

    val dReq = Output(Bool())
    val dAddr = Output(UInt(64.W))
    val dWen = Output(Bool())
    val dWData = Output(UInt(64.W))
    val pReq = Output(Bool())
    val pAddr = Output(UInt(64.W))
    val ptwReq = Output(Bool())
    val ptwAddr = Output(UInt(64.W))

    val done = Output(Bool())
    val result = Output(UInt(64.W))
    val rdWen = Output(Bool())
    val isMmio = Output(Bool())
    val pageFault = Output(Bool())
    val pageFaultCause = Output(UInt(64.W))
    val pageFaultAddr = Output(UInt(64.W))
  })

  val dut = Module(new LSU)
  dut.prf(0).data := io.prs1Data
  dut.prf(1).data := io.prs2Data

  dut.late.req := io.req
  dut.late.bits.prs1 := io.prs1
  dut.late.bits.prs2 := io.prs2
  dut.late.bits.imm := io.imm
  dut.late.bits.size := io.size
  dut.late.bits.sign_ext := io.signExt
  dut.late.bits.r_en := io.rEn
  dut.late.bits.w_en := io.wEn

  dut.satp_in := io.satp
  dut.priv_in := io.priv
  dut.sfence_vma := io.sfenceVma

  dut.dcache.ack := io.dAck
  dut.dcache.done := io.dDone
  dut.dcache.rdata := io.dRData
  dut.perip.ack := io.pAck
  dut.perip.done := io.pDone
  dut.perip.rdata := io.pRData
  dut.ptw_port.ack := io.ptwAck
  dut.ptw_port.done := io.ptwDone
  dut.ptw_port.rdata := io.ptwRData

  io.dReq := dut.dcache.req
  io.dAddr := dut.dcache.addr
  io.dWen := dut.dcache.wen
  io.dWData := dut.dcache.wdata
  io.pReq := dut.perip.req
  io.pAddr := dut.perip.addr
  io.ptwReq := dut.ptw_port.req
  io.ptwAddr := dut.ptw_port.addr

  io.done := dut.late.done
  io.result := dut.late.bits.result
  io.rdWen := dut.late.bits.rd_wen
  io.isMmio := dut.late.bits.is_mmio
  io.pageFault := dut.late.bits.page_fault
  io.pageFaultCause := dut.late.bits.page_fault_cause
  io.pageFaultAddr := dut.late.bits.page_fault_addr
}

class LSUTest extends AnyFlatSpec with Matchers {
  behavior of "LSU"

  private def defaults(dut: LSUHarness): Unit = {
    dut.reset.poke(true.B)
    dut.io.req.poke(false.B)
    dut.io.prs1.poke(1.U)
    dut.io.prs2.poke(2.U)
    dut.io.prs1Data.poke(0.U)
    dut.io.prs2Data.poke(0.U)
    dut.io.imm.poke(0.U)
    dut.io.size.poke(3.U)
    dut.io.signExt.poke(false.B)
    dut.io.rEn.poke(false.B)
    dut.io.wEn.poke(false.B)
    dut.io.satp.poke(0.U)
    dut.io.priv.poke(3.U)
    dut.io.sfenceVma.poke(false.B)

    dut.io.dAck.poke(false.B)
    dut.io.dDone.poke(false.B)
    dut.io.dRData.poke(0.U)
    dut.io.pAck.poke(false.B)
    dut.io.pDone.poke(false.B)
    dut.io.pRData.poke(0.U)
    dut.io.ptwAck.poke(false.B)
    dut.io.ptwDone.poke(false.B)
    dut.io.ptwRData.poke(0.U)
    dut.clock.step(2)
    dut.reset.poke(false.B)
    dut.clock.step()
  }

  private def launchReq(
      dut: LSUHarness,
      base: BigInt,
      imm: BigInt,
      rEn: Boolean,
      wEn: Boolean,
      storeData: BigInt = 0
  ): Unit = {
    dut.io.prs1Data.poke(base.U)
    dut.io.prs2Data.poke(storeData.U)
    dut.io.imm.poke(imm.U)
    dut.io.rEn.poke(rEn.B)
    dut.io.wEn.poke(wEn.B)
    dut.io.req.poke(true.B)
    dut.clock.step()
    dut.io.req.poke(false.B)
  }

  it should "execute a bare load via dcache path" in {
    simulate(new LSUHarness) { dut =>
      defaults(dut)

      val vaddr = BigInt("80000020", 16)
      val aligned = BigInt("80000020", 16)
      dut.io.dAck.poke(true.B)
      dut.io.dDone.poke(true.B)
      dut.io.dRData.poke("h0123456789abcdef".U)
      launchReq(dut, base = vaddr, imm = 0, rEn = true, wEn = false)

      stepUntil(dut.io.done.peek().litToBoolean, 10, "load done") { dut.clock.step() }
      dut.io.isMmio.expect(false.B)
      dut.io.dAddr.expect(aligned.U)
      dut.io.rdWen.expect(true.B)
      dut.io.result.expect("h0123456789abcdef".U)
      dut.io.pageFault.expect(false.B)
    }
  }

  it should "execute a bare store and suppress writeback" in {
    simulate(new LSUHarness) { dut =>
      defaults(dut)

      val vaddr = BigInt("80000040", 16)
      launchReq(
        dut,
        base = vaddr,
        imm = 0,
        rEn = false,
        wEn = true,
        storeData = BigInt("deadbeefcafebabe", 16)
      )
      dut.io.dAck.poke(true.B)
      dut.io.dDone.poke(true.B)

      stepUntil(dut.io.done.peek().litToBoolean, 10, "store done") { dut.clock.step() }
      dut.io.dWen.expect(true.B)
      dut.io.dAddr.expect(vaddr.U)
      dut.io.rdWen.expect(false.B)
      dut.io.pageFault.expect(false.B)
    }
  }

  it should "raise page fault when PTW returns invalid root PTE" in {
    simulate(new LSUHarness) { dut =>
      defaults(dut)

      val sv39Satp = (BigInt(8) << 60) | BigInt("100", 16)
      val vaddr = BigInt("80001000", 16)
      dut.io.satp.poke(sv39Satp.U)
      launchReq(dut, base = vaddr, imm = 0, rEn = true, wEn = false)

      stepUntil(dut.io.ptwReq.peek().litToBoolean, 30, "PTW issues level2 request") { dut.clock.step() }
      dut.io.ptwAck.poke(true.B)
      dut.clock.step()
      dut.io.ptwAck.poke(false.B)

      // level-2 invalid PTE (V=0) -> PTW fault.
      dut.io.ptwDone.poke(true.B)
      dut.io.ptwRData.poke(0.U)
      dut.clock.step()
      dut.io.ptwDone.poke(false.B)

      stepUntil(dut.io.done.peek().litToBoolean, 40, "fault response from LSU") { dut.clock.step() }
      dut.io.pageFault.expect(true.B)
      dut.io.pageFaultCause.expect(13.U)
      dut.io.pageFaultAddr.expect(vaddr.U)
      dut.io.rdWen.expect(false.B)
    }
  }
}
