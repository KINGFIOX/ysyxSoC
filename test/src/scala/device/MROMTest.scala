package ysyx.device.test

import chisel3._
import chisel3.util._
import chisel3.simulator.EphemeralSimulator._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

// Testable MROM read state machine without DPI dependency.
// Mirrors the AXI4 read FSM from AXI4MROM: idle → waitReady,
// plus write rejection (RESP_DECERR).
class MROMAXIReadFSM extends Module {
  val io = IO(new Bundle {
    val ar_valid = Input(Bool())
    val ar_ready = Output(Bool())
    val ar_addr = Input(UInt(32.W))
    val r_valid = Output(Bool())
    val r_ready = Input(Bool())
    val r_data = Output(UInt(32.W))
    val r_resp = Output(UInt(2.W))
    val r_last = Output(Bool())
    val aw_valid = Input(Bool())
    val aw_ready = Output(Bool())
    val w_valid = Input(Bool())
    val w_ready = Output(Bool())
    val b_valid = Output(Bool())
    val b_ready = Input(Bool())
    val b_resp = Output(UInt(2.W))
  })

  // Simple ROM: addr(3,2) selects word; data = addr + 0x100
  val rom = VecInit(Seq.tabulate(4)(i => (i * 4 + 0x100).U(32.W)))

  object ReadState extends ChiselEnum {
    val idle, waitReady = Value
  }
  val read_state = RegInit(ReadState.idle)
  val ar_fire = io.ar_valid && io.ar_ready
  val r_fire = io.r_valid && io.r_ready

  read_state := Mux(
    read_state === ReadState.idle,
    Mux(ar_fire, ReadState.waitReady, ReadState.idle),
    Mux(r_fire, ReadState.idle, ReadState.waitReady)
  )

  io.ar_ready := read_state === ReadState.idle
  io.r_data := RegEnable(rom(io.ar_addr(3, 2)), ar_fire)
  io.r_resp := 0.U
  io.r_last := true.B
  io.r_valid := read_state === ReadState.waitReady

  object WriteState extends ChiselEnum {
    val idle, done = Value
  }
  val write_state = RegInit(WriteState.idle)
  val aw_received = RegInit(false.B)
  val w_received = RegInit(false.B)

  io.aw_ready := write_state === WriteState.idle && !aw_received
  io.w_ready := write_state === WriteState.idle && !w_received
  io.b_valid := write_state === WriteState.done
  io.b_resp := 3.U // RESP_DECERR

  switch(write_state) {
    is(WriteState.idle) {
      when(io.aw_valid && io.aw_ready) { aw_received := true.B }
      when(io.w_valid && io.w_ready) { w_received := true.B }
      val aw_done = aw_received || (io.aw_valid && io.aw_ready)
      val w_done = w_received || (io.w_valid && io.w_ready)
      when(aw_done && w_done) {
        write_state := WriteState.done
        aw_received := false.B
        w_received := false.B
      }
    }
    is(WriteState.done) {
      when(io.b_valid && io.b_ready) { write_state := WriteState.idle }
    }
  }
}

class MROMTest extends AnyFlatSpec with Matchers {
  behavior of "MROM AXI Read FSM"

  it should "accept a read request and return data" in {
    simulate(new MROMAXIReadFSM) { dut =>
      dut.io.ar_valid.poke(false.B)
      dut.io.r_ready.poke(false.B)
      dut.io.aw_valid.poke(false.B)
      dut.io.w_valid.poke(false.B)
      dut.io.b_ready.poke(false.B)
      dut.clock.step()

      // ar_ready should be high in idle
      dut.io.ar_ready.expect(true.B)

      // Issue read at addr 0x04 → rom(1) = 0x104
      dut.io.ar_addr.poke(0x04.U)
      dut.io.ar_valid.poke(true.B)
      dut.clock.step()
      dut.io.ar_valid.poke(false.B)

      // Now r_valid should be high
      dut.io.r_valid.expect(true.B)
      dut.io.r_data.expect(0x104.U)
      dut.io.r_resp.expect(0.U)
      dut.io.r_last.expect(true.B)

      // Complete read handshake
      dut.io.r_ready.poke(true.B)
      dut.clock.step()
      dut.io.r_ready.poke(false.B)

      // Back to idle
      dut.io.r_valid.expect(false.B)
      dut.io.ar_ready.expect(true.B)
    }
  }

  it should "reject writes with DECERR" in {
    simulate(new MROMAXIReadFSM) { dut =>
      dut.io.ar_valid.poke(false.B)
      dut.io.r_ready.poke(false.B)
      dut.io.aw_valid.poke(false.B)
      dut.io.w_valid.poke(false.B)
      dut.io.b_ready.poke(false.B)
      dut.clock.step()

      // Issue write: aw and w simultaneously
      dut.io.aw_valid.poke(true.B)
      dut.io.w_valid.poke(true.B)
      dut.clock.step()
      dut.io.aw_valid.poke(false.B)
      dut.io.w_valid.poke(false.B)

      // b channel should have DECERR
      dut.io.b_valid.expect(true.B)
      dut.io.b_resp.expect(3.U) // RESP_DECERR

      // Complete write response
      dut.io.b_ready.poke(true.B)
      dut.clock.step()
      dut.io.b_ready.poke(false.B)

      dut.io.b_valid.expect(false.B)
    }
  }

  it should "handle sequential reads" in {
    simulate(new MROMAXIReadFSM) { dut =>
      dut.io.ar_valid.poke(false.B)
      dut.io.r_ready.poke(false.B)
      dut.io.aw_valid.poke(false.B)
      dut.io.w_valid.poke(false.B)
      dut.io.b_ready.poke(false.B)
      dut.clock.step()

      for (i <- 0 until 4) {
        dut.io.ar_addr.poke((i * 4).U)
        dut.io.ar_valid.poke(true.B)
        dut.clock.step()
        dut.io.ar_valid.poke(false.B)

        dut.io.r_valid.expect(true.B)
        dut.io.r_data.expect((i * 4 + 0x100).U)

        dut.io.r_ready.poke(true.B)
        dut.clock.step()
        dut.io.r_ready.poke(false.B)
      }
    }
  }
}
