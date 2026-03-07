package ysyx

import chisel3._
import chisel3.util._

class psram_cmd
    extends FixedIOExtModule(
      new Bundle {
        val clock = Input(Clock())
        val valid = Input(Bool())
        val cmd = Input(UInt(8.W))
        val addr = Input(UInt(32.W))
        val wdata = Input(UInt(8.W))
        val rdata = Output(UInt(8.W))
      }
    )

// eb: write (1, 4, 4)
// 38: read (1, 4, 4)
class psram extends RawModule {
  val io = IO(Flipped(new QSPIIO))
  val systemReset = IO(Input(AsyncReset()))
  val ce_n = io.ce_n.asAsyncReset
  val sckRise = io.sck.asClock
  val sckFall = (!io.sck).asClock
  val module = withClockAndReset(sckRise, ce_n) { Module(new Impl) }
  val misoOut = withClockAndReset(sckFall, ce_n) { RegNext(module.io.miso) }
  val misoEnOut = withClockAndReset(sckFall, ce_n) {
    RegNext(module.io.misoEn, false.B)
  }
  module.io.mosi := TriStateInBuf(io.dio, misoOut, misoEnOut)
  module.io.systemReset := systemReset
  class Impl extends Module with RequireAsyncReset {
    val io = IO(new Bundle {
      val miso = Output(UInt(4.W))
      val mosi = Input(UInt(4.W))
      val misoEn = Output(Bool())
      val systemReset = Input(AsyncReset())
    })

    // mode
    val qpiMode = withClockAndReset(this.clock, io.systemReset) {
      RegInit(false.B)
    }

    object State extends ChiselEnum {
      val cmd, addr, wait_read, data = Value
    }
    val counter = RegInit(0.U(5.W))
    val state = RegInit(State.cmd)
    val cmd = RegInit(0.U(8.W))
    val addr = RegInit(0.U(32.W));
    val base = RegInit(0.U(24.W)); val offset = RegInit(0.U(10.W)) // wrapping
    val wdataH = RegInit(0.U(4.W))
    val u0_psram_cmd = Module(new psram_cmd)
    u0_psram_cmd.io.clock := this.clock
    u0_psram_cmd.io.valid := false.B
    u0_psram_cmd.io.cmd := cmd
    u0_psram_cmd.io.addr := Cat(base, offset)
    u0_psram_cmd.io.wdata := Cat(wdataH, io.mosi)
    val rdata = u0_psram_cmd.io.rdata

    io.miso := 0.U
    io.misoEn := false.B // default

    switch(state) {
      is(State.cmd) {
        counter := counter + 1.U
        when(qpiMode) { // qpi mode
          val next_cmd = Cat(cmd(3, 0), io.mosi)
          cmd := next_cmd
          when(
            counter === 1.U
          ) { // only allow: qspi -> qpi; not allow: qpi -> qspi
            counter := 0.U
            state := State.addr
          }
        }.otherwise { // qspi mode
          val next_cmd = Cat(cmd(6, 0), io.mosi(0))
          cmd := next_cmd
          when(counter === 7.U) {
            counter := 0.U
            state := State.addr // default
            when(next_cmd === "h35".U) {
              qpiMode := true.B
              state := State.cmd // TODO: 一般设置完成以后, 总线事务就结束了
            }
          }
        }
      }
      is(State.addr) {
        counter := counter + 1.U
        val next_addr = Cat(0.U(8.W), addr(19, 0), io.mosi)
        addr := next_addr; base := next_addr(23, 10); offset := next_addr(9, 0)
        when(counter === 5.U) {
          counter := 0.U
          assert(
            cmd === "heb".U || cmd === "h38".U,
            cf"Assert failed: Unsupportted command `${cmd}%x`"
          )
          when(cmd === "heb".U) {
            state := State.wait_read
          }.elsewhen(cmd === "h38".U) {
            state := State.data
          }
        }
      }
      is(State.wait_read) {
        counter := counter + 1.U
        when(counter === 5.U) {
          counter := 0.U
          u0_psram_cmd.io.valid := true.B // pulse
          state := State.data
        }
      }
      is(State.data) {
        assert(cmd === "heb".U || cmd === "h38".U, "impossible")
        when(cmd === "heb".U) { // read
          io.misoEn := true.B
          when(counter === 0.U) {
            counter := 1.U
            io.miso := rdata(7, 4)
          }.otherwise { // counter === 1
            counter := 0.U
            io.miso := rdata(3, 0)
            u0_psram_cmd.io.valid := true.B
            val next_offset = offset + 1.U
            u0_psram_cmd.io.addr := Cat(base, next_offset)
            offset := next_offset
          }
        }.elsewhen(cmd === "h38".U) { // write
          when(counter === 0.U) {
            counter := 1.U
            wdataH := io.mosi
          }.otherwise { // counter === 1
            counter := 0.U
            offset := offset + 1.U
            u0_psram_cmd.io.valid := true.B
          }
        }
      }
    }
  }
}
