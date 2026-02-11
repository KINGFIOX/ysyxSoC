package ysyx

import chisel3._
import chisel3.util._

class flash_cmd extends BlackBox {
  val io = IO(new Bundle {
    val clock = Input(Clock())
    val valid = Input(Bool())
    val cmd = Input(UInt(8.W))
    val addr = Input(UInt(32.W))
    val data = Output(UInt(32.W))
  })
}

// 考虑了 “窄传输” 的 Flash
// SPI:
// 1. 地址: 大端字节序 大端位序
// 2. 数据: 字节序随ISA(小端) 大端位序
class flash extends RawModule {
  val io = IO(Flipped(new SPIIO(1)))
  val reset = io.ss.asBool.asAsyncReset
  val clock = io.sck.asClock
  val module = withClockAndReset(clock, reset) { Module(new Impl) }
  class Impl extends Module with RequireAsyncReset {
    val io = IO(new Bundle {
      val miso = Output(Bool())
      val mosi = Input(Bool())
    })
    object State extends ChiselEnum {
      val cmd, addr, data = Value
    }
    val state = RegInit(State.cmd)
    val counter = RegInit(0.U(5.W))
    val cmd = RegInit(0.U(8.W))
    val addr = RegInit(0.U(24.W))
    val u0_flash_cmd = Module(new flash_cmd)
    u0_flash_cmd.io.clock := this.clock
    u0_flash_cmd.io.valid := false.B
    u0_flash_cmd.io.addr := addr
    u0_flash_cmd.io.cmd := cmd
    val rdata = u0_flash_cmd.io.data
    val data = RegInit(0.U(8.W))
    io.miso := true.B
    switch(state) {
      is(State.cmd) {
        counter := counter + 1.U
        cmd := Cat( cmd(6, 0), io.mosi )
        when(counter === 7.U) {
          counter := 0.U // suppress increment
          state := State.addr
        }
      }
      is(State.addr) {
        counter := counter + 1.U
        val next_addr = Cat( addr(22, 0), io.mosi )
        addr := next_addr
        when(counter === 23.U) {
          counter := 0.U
          u0_flash_cmd.io.valid := true.B
          u0_flash_cmd.io.addr := next_addr
          state := State.data
        }
      }
      is(State.data) {
        counter := counter + 1.U
        data := Cat( data(6, 0), false.B )
        io.miso := data(7)
        when(counter === 0.U) {
          io.miso := rdata(7)
          data := Cat( rdata(6, 0), false.B )
        } .elsewhen( counter === 7.U ) {
          val next_addr = addr + 1.U
          addr := next_addr
          u0_flash_cmd.io.valid := true.B
          u0_flash_cmd.io.addr := next_addr
          counter := 0.U // reset
        }
      }
    }
  }
  io.miso := Mux(io.ss.asBool, true.B, module.io.miso)
  module.io.mosi := io.mosi
}
