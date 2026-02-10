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
    val addr = RegInit(0.U(32.W)); val next_addr = Cat( 0.U(8.W), addr(22, 0), io.mosi )
    val ren = WireDefault(false.B)
    val u0_flash_cmd = Module(new flash_cmd)
    u0_flash_cmd.io.clock := this.clock
    u0_flash_cmd.io.valid := ren
    u0_flash_cmd.io.addr := next_addr
    u0_flash_cmd.io.cmd := cmd
    val rdata = u0_flash_cmd.io.data
    val data_bswap = Cat( rdata(7, 0), rdata(15, 8), rdata(23, 16), rdata(31, 24) )
    val data = RegInit(0.U(32.W))
    io.miso := data(31)
    switch(state) {
      is(State.cmd) {
        counter := counter + 1.U
        val next_cmd = Cat( cmd(6, 0), io.mosi )
        cmd := next_cmd
        when(counter === 7.U) {
          counter := 0.U // suppress increment
          state := State.addr
        }
      }
      is(State.addr) {
        counter := counter + 1.U
        addr := next_addr
        when(counter === 23.U) {
          counter := 0.U // suppress increment
          ren := true.B
          state := State.data
        }
      }
      is(State.data) {
        counter := counter + 1.U
        when(counter === 0.U) {
          io.miso := data_bswap(31)
          data := Cat( data_bswap(30, 0), false.B )
        } .otherwise {
          data := Cat( data(30, 0), false.B )
        }
      }
    }
  }
  io.miso := Mux(io.ss.asBool, true.B, module.io.miso)
  module.io.mosi := io.mosi
}
