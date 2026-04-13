package ysyx.sim

import chisel3._
import chisel3.util._
import chisel3.util.circt.dpi._

import freechips.rocketchip.amba.axi4._
import org.chipsalliance.cde.config.Parameters
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.util._
import ysyx.SoCConfig

class AXI4SDRAM(address: Seq[AddressSet])(implicit p: Parameters)
    extends LazyModule {
  val beatBytes = 8
  val node = AXI4SlaveNode(
    Seq(
      AXI4SlavePortParameters(
        Seq(
          AXI4SlaveParameters(
            address = address,
            executable = true,
            supportsWrite = TransferSizes(1, 64),
            supportsRead = TransferSizes(1, 64),
            interleavedId = Some(0) // no interleaved
          )
        ),
        beatBytes = beatBytes
      )
    )
  )

  lazy val module = new Impl
  class Impl extends LazyModuleImp(this) {
    val (in, edgeIn) = node.in(0)
    val axiParams = edgeIn.bundle
    val mem = Module(new SDRAMImpl(axiParams))
    mem.io <> in
  }
}

class SDRAMImpl(axiParams: AXI4BundleParameters) extends Module {
  val io = IO(Flipped(new AXI4Bundle(axiParams)))
  private val beatBytes = axiParams.dataBits / 8

  // ==================== Read Path (independent) ====================
  // RawClockedNonVoidFunctionCall results behave like registers (1-cycle latency),
  // so we need a wait state between issuing the DPI read and asserting r.valid.
  object RState extends ChiselEnum {
    val rIdle, rWait, rValid = Value
  }
  private val rState = RegInit(RState.rIdle)
  private val rAddr = Reg(UInt(axiParams.addrBits.W))
  private val rId = Reg(UInt(axiParams.idBits.W))
  private val rLen = Reg(UInt(axiParams.lenBits.W))

  private val readEn = WireInit(false.B)
  private val rOffset = rAddr.pad(64)
  private val read0 = RawClockedNonVoidFunctionCall("sdram_read", UInt(16.W))(clock, readEn, rOffset)
  private val read1 = RawClockedNonVoidFunctionCall("sdram_read", UInt(16.W))(clock, readEn, (rOffset + 2.U).pad(64))
  private val read2 = RawClockedNonVoidFunctionCall("sdram_read", UInt(16.W))(clock, readEn, (rOffset + 4.U).pad(64))
  private val read3 = RawClockedNonVoidFunctionCall("sdram_read", UInt(16.W))(clock, readEn, (rOffset + 6.U).pad(64))

  io.ar.ready := rState === RState.rIdle
  io.r.valid := false.B
  io.r.bits := DontCare
  io.r.bits.data := Cat(read3, read2, read1, read0)
  io.r.bits.resp := 0.U
  io.r.bits.id := rId
  io.r.bits.last := rLen === 0.U

  switch(rState) {
    is(RState.rIdle) {
      when(io.ar.fire) {
        rAddr := io.ar.bits.addr - SoCConfig.sdramBase.U
        rId := io.ar.bits.id
        rLen := io.ar.bits.len
        rState := RState.rWait
      }
    }
    is(RState.rWait) {
      readEn := true.B
      rState := RState.rValid
    }
    is(RState.rValid) {
      io.r.valid := true.B
      when(io.r.fire) {
        when(rLen === 0.U) {
          rState := RState.rIdle
        }.otherwise {
          rLen := rLen - 1.U
          rAddr := rAddr + beatBytes.U
          rState := RState.rWait
        }
      }
    }
  }

  // ==================== Write Path (independent, AW/W decoupled) ====================
  // format: off
  private val awQueue = Module( new Queue(new AXI4BundleAW(axiParams), entries = 4))
  private val wQueue = Module( new Queue(new AXI4BundleW(axiParams), entries = 4))
  // format: on

  awQueue.io.enq.valid := io.aw.valid
  awQueue.io.enq.bits := io.aw.bits
  io.aw.ready := awQueue.io.enq.ready

  wQueue.io.enq.valid := io.w.valid
  wQueue.io.enq.bits := io.w.bits
  io.w.ready := wQueue.io.enq.ready

  object WPState extends ChiselEnum {
    val wpIdle, wpBurst, wpResp = Value
  }
  private val wpState = RegInit(WPState.wpIdle)
  private val wAddr = Reg(UInt(axiParams.addrBits.W))
  private val wId = Reg(UInt(axiParams.idBits.W))

  private val writeEn = WireInit(false.B)
  private val writeAddr = WireInit(0.U(axiParams.addrBits.W))
  private val writeData = WireInit(0.U(axiParams.dataBits.W))
  private val writeStrb = WireInit(0.U(beatBytes.W))

  for (i <- 0 until beatBytes) {
    RawClockedVoidFunctionCall("sdram_write")(
      clock,
      writeEn && writeStrb(i),
      (writeAddr + i.U).pad(64),
      writeData(i * 8 + 7, i * 8)
    )
  }

  awQueue.io.deq.ready := false.B
  wQueue.io.deq.ready := false.B
  io.b.valid := false.B
  io.b.bits := DontCare
  io.b.bits.resp := 0.U
  io.b.bits.id := wId

  switch(wpState) {
    is(WPState.wpIdle) {
      when(awQueue.io.deq.valid) {
        awQueue.io.deq.ready := true.B
        wAddr := awQueue.io.deq.bits.addr - SoCConfig.sdramBase.U
        wId := awQueue.io.deq.bits.id
        wpState := WPState.wpBurst
      }
    }
    is(WPState.wpBurst) {
      when(wQueue.io.deq.valid) {
        wQueue.io.deq.ready := true.B
        writeEn := true.B
        writeAddr := wAddr
        writeData := wQueue.io.deq.bits.data
        writeStrb := wQueue.io.deq.bits.strb
        wAddr := wAddr + beatBytes.U
        when(wQueue.io.deq.bits.last) {
          wpState := WPState.wpResp
        }
      }
    }
    is(WPState.wpResp) {
      io.b.valid := true.B
      when(io.b.fire) {
        wpState := WPState.wpIdle
      }
    }
  }
}
