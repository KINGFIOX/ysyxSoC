package ysyx

import chisel3._
import chisel3.util._
import chisel3.experimental.Analog

import freechips.rocketchip.amba.apb._
import org.chipsalliance.cde.config.Parameters
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.util._

class QSPIIO extends Bundle {
  val sck  = Output(Bool())
  val ce_n = Output(Bool())
  val dio  = Analog(4.W)
}

class APBPSRAM(address: Seq[AddressSet])(implicit p: Parameters)
    extends LazyModule {
  val node = APBSlaveNode(
    Seq(
      APBSlavePortParameters(
        Seq(
          APBSlaveParameters(
            address = address,
            executable = true,
            supportsRead = true,
            supportsWrite = true
          )
        ),
        beatBytes = 4
      )
    )
  )

  lazy val module = new Impl
  class Impl extends LazyModuleImp(this) {
    val (in, _) = node.in(0)
    val qspi_bundle = IO(new QSPIIO)

    val mqspi = Module(new QSPI)
    mqspi.io.in <> in
    qspi_bundle.sck  := mqspi.io.sck
    qspi_bundle.ce_n := mqspi.io.ce_n
    mqspi.io.miso := TriStateInBuf(qspi_bundle.dio, mqspi.io.mosiOut, mqspi.io.mosiEn)
  }
}

// ═══════════════════════════════════════════════════════════════════
// Clock Generator
// ═══════════════════════════════════════════════════════════════════

class QSPIClgen(dividerLen: Int) extends Module {
  val io = IO(new Bundle {
    val go      = Input(Bool())
    val tip     = Input(Bool())
    val lastClk = Input(Bool())
    val divider = Input(UInt(dividerLen.W))
    val clkOut  = Output(Bool())
    val posEdge = Output(Bool())
    val negEdge = Output(Bool())
  })

  private val cnt    = RegInit(0.U(dividerLen.W))
  private val clkOut = RegInit(false.B)

  private val cntZero = cnt === 0.U
  private val cntOne  = cnt === 1.U
  private val divZero = !io.divider.orR

  when(!io.tip || cntZero) {
    cnt := io.divider
  }.otherwise {
    cnt := cnt - 1.U
  }

  // clkOut toggles every half period;
  // lastClk → clkOut ensures the final edge is always 1→0
  when(io.tip && cntZero && (!io.lastClk || clkOut)) {
    clkOut := ~clkOut
  }

  io.posEdge := RegNext(
    (io.tip && !clkOut && cntOne) ||
      (divZero && clkOut) ||
      (divZero && io.go && !io.tip),
    false.B
  )

  io.negEdge := RegNext(
    (io.tip && clkOut && cntOne) ||
      (divZero && !clkOut && io.tip),
    false.B
  )

  io.clkOut := clkOut
}

// ═══════════════════════════════════════════════════════════════════
// Shift Register
// ═══════════════════════════════════════════════════════════════════

class QSPIShift(maxChar: Int = 128) extends Module {
  private val cBits    = log2Ceil(maxChar) // 7
  private val nNibbles = maxChar >> 2 // 32
  private val idxBits  = log2Ceil(nNibbles) // 5

  val io = IO(new Bundle {
    val len4    = Input(UInt(cBits.W))
    val sOutLen = Input(UInt(cBits.W))
    val go      = Input(Bool())
    val posEdge = Input(Bool())
    val negEdge = Input(Bool())
    val tip     = Output(Bool())
    val last    = Output(Bool())
    val wen     = Input(Bool())
    val pIn     = Input(UInt(maxChar.W))
    val pOut    = Output(UInt(maxChar.W))
    val sClk    = Input(Bool())
    val sIn     = Input(UInt(4.W))
    val sOut    = Output(UInt(4.W))
    val sOutEn  = Output(Bool())
  })

  private val data    = RegInit(VecInit(Seq.fill(nNibbles)(0.U(4.W))))
  private val sOut    = RegInit(0.U(4.W))
  private val cnt     = RegInit(0.U(cBits.W))
  private val regLen4 = RegInit(0.U(cBits.W))
  private val outCnt  = RegInit(0.U(cBits.W))

  private object State extends ChiselEnum {
    val idle, mosi, miso = Value
  }
  private val state = RegInit(State.idle)

  private val last   = !cnt.orR
  private val bitPos = (cnt - 1.U)(idxBits - 1, 0)
  private val rxClk  = io.posEdge && !last
  dontTouch(rxClk)
  private val txClk  = io.negEdge && !last
  dontTouch(txClk)

  switch(state) {
    is(State.idle) {
      cnt := regLen4

      when(io.wen) {
        for (i <- 0 until nNibbles) {
          data(i) := io.pIn(i * 4 + 3, i * 4)
        }
        regLen4 := io.len4
        outCnt  := io.sOutLen

        val pInNibbles = VecInit((0 until nNibbles).map(i => io.pIn(i * 4 + 3, i * 4)))
        val firstTxIdx = (io.len4 - 1.U)(idxBits - 1, 0)
        sOut := pInNibbles(firstTxIdx)
      }

      when(io.go && regLen4.orR) {
        state := State.mosi
      }
    }

    is(State.mosi) {
      when(io.posEdge) { cnt := cnt - 1.U }
      when(rxClk)      { data(bitPos) := io.sIn }
      when(txClk) {
        sOut := data(bitPos)
        when(outCnt.orR) { outCnt := outCnt - 1.U }
      }

      when(last && io.posEdge) {
        state := State.idle
      }.elsewhen(!outCnt.orR) {
        state := State.miso
      }
    }

    is(State.miso) {
      when(io.posEdge) { cnt := cnt - 1.U }
      when(rxClk)      { data(bitPos) := io.sIn }

      when(last && io.posEdge) {
        state := State.idle
      }
    }
  }

  io.pOut   := data.asUInt
  io.tip    := state =/= State.idle
  io.last   := last
  io.sOut   := sOut
  io.sOutEn := state === State.mosi
}

// ═══════════════════════════════════════════════════════════════════
// QSPI Top (APB → QSPI controller for PSRAM)
// ═══════════════════════════════════════════════════════════════════

class QSPI extends Module {
  private val dividerLen = 16
  private val maxChar    = 128
  private val cBits      = log2Ceil(maxChar) // 7

  val io = IO(new Bundle {
    val in      = Flipped(new APBBundle(APBBundleParameters(addrBits = 32, dataBits = 32)))
    val sck     = Output(Bool())
    val ce_n    = Output(Bool())
    val mosiOut = Output(UInt(4.W))
    val mosiEn  = Output(Bool())
    val miso    = Input(UInt(4.W))
  })

  private def expandCmd(cmd: Int): BigInt = {
    var result: BigInt = 0
    for (i <- 7 to 0 by -1) {
      result = (result << 4) | ((cmd >> i) & 1)
    }
    result
  }
  // private val qspiWriteCmdExp = expandCmd(0x38).U(32.W)
  // private val qspiReadCmdExp  = expandCmd(0xEB).U(32.W)
  private val qspiWriteCmdExp = (0x38).U(8.W)
  private val qspiReadCmdExp  = (0xEB).U(8.W)

  private val mosiEnOut = WireDefault(false.B)
  private val mosiOut   = WireDefault(0.U(4.W))

  private val divider = RegInit(0.U(dividerLen.W))

  // ─── Sub-modules ───────────────────────────────────────────
  private val clgen = Module(new QSPIClgen(dividerLen))
  private val shift = Module(new QSPIShift(maxChar))

  shift.io.len4    := 0.U
  shift.io.sOutLen := 0.U
  shift.io.go      := false.B
  shift.io.posEdge := clgen.io.posEdge
  shift.io.negEdge := clgen.io.negEdge
  shift.io.wen     := false.B
  shift.io.pIn     := 0.U
  shift.io.sClk    := clgen.io.clkOut
  shift.io.sIn     := io.miso
  mosiOut          := shift.io.sOut
  mosiEnOut        := shift.io.sOutEn
  io.mosiOut       := mosiOut
  io.mosiEn        := mosiEnOut

  clgen.io.go      := false.B
  clgen.io.tip     := shift.io.tip
  clgen.io.divider := divider
  clgen.io.lastClk := shift.io.last

  // ─── APB default outputs ──────────────────────────────────
  io.in.pready  := false.B
  io.in.prdata  := 0.U
  io.in.pslverr := false.B

  // ─── QSPI outputs ────────────────────────────────────────
  val ce = WireDefault(false.B)
  io.sck := clgen.io.clkOut
  io.ce_n := ! ce

  // ─── Write data calculation (little-endian byte swap) ─────
  private val wdata     = WireDefault(0.U(maxChar.W))
  private val wCharLen4 = WireDefault(0.U(cBits.W))

  switch(io.in.pstrb) {
    is("b0001".U) {
      wdata     := Cat(qspiWriteCmdExp, io.in.paddr(23, 0), io.in.pwdata(7, 0))
      wCharLen4 := ((8 + 24 + 8) >> 2).U
    }
    is("b0010".U) {
      wdata     := Cat(qspiWriteCmdExp, io.in.paddr(23, 0) + 1.U, io.in.pwdata(15, 8))
      wCharLen4 := ((8 + 24 + 8) >> 2).U
    }
    is("b0100".U) {
      wdata     := Cat(qspiWriteCmdExp, io.in.paddr(23, 0) + 2.U, io.in.pwdata(23, 16))
      wCharLen4 := ((8 + 24 + 8) >> 2).U
    }
    is("b1000".U) {
      wdata     := Cat(qspiWriteCmdExp, io.in.paddr(23, 0) + 3.U, io.in.pwdata(31, 24))
      wCharLen4 := ((8 + 24 + 8) >> 2).U
    }
    is("b0011".U) {
      val swapped = Cat(io.in.pwdata(7, 0), io.in.pwdata(15, 8))
      wdata     := Cat(qspiWriteCmdExp, io.in.paddr(23, 0), swapped)
      wCharLen4 := ((8 + 24 + 16) >> 2).U
    }
    is("b1100".U) {
      val swapped = Cat(io.in.pwdata(23, 16), io.in.pwdata(31, 24))
      wdata     := Cat(qspiWriteCmdExp, io.in.paddr(23, 0) + 2.U, swapped)
      wCharLen4 := ((8 + 24 + 16) >> 2).U
    }
    is("b1111".U) {
      val swapped = Cat(io.in.pwdata(7, 0), io.in.pwdata(15, 8), io.in.pwdata(23, 16), io.in.pwdata(31, 24))
      wdata     := Cat(qspiWriteCmdExp, io.in.paddr(23, 0), swapped)
      wCharLen4 := ((8 + 24 + 32) >> 2).U
    }
  }

  // ─── Transfer-complete detection ─────────────────────────
  private val tipDone = shift.io.tip && shift.io.last && clgen.io.posEdge

  // ─── State machine ────────────────────────────────────────
  object State extends ChiselEnum {
    val initSetup, initAccess, idle, setup, access, ready = Value
  }
  private val state = RegInit(State.initSetup)
  private val isWriteReg = RegInit(false.B)

  switch(state) {
    is(State.initSetup) {
      shift.io.wen := true.B
      shift.io.len4 := ( (32) >> 2 ).U
      shift.io.pIn := expandCmd(0x35).U(32.W)
      shift.io.sOutLen := ( (32) >> 2 ).U
      state := State.initAccess
    }
    is(State.initAccess) {
      ce := true.B
      shift.io.go := true.B
      clgen.io.go := true.B
      when(tipDone) {
        state := State.idle
      }
    }
    is(State.idle) {
      when(io.in.psel) {
        state := State.setup
        assert(io.in.paddr(1, 0) === 0.U, cf"QSPI: unaligned address `${io.in.paddr}%x`")
      }
    }

    is(State.setup) {
      isWriteReg := io.in.pwrite

      val nextCharLen4 = WireDefault(0.U(cBits.W))
      val nextData     = WireDefault(0.U(maxChar.W))
      val nextSOutLen4 = WireDefault(0.U(cBits.W))

      when(io.in.pwrite) {
        nextCharLen4 := wCharLen4
        nextData     := wdata
        nextSOutLen4 := wCharLen4
      }.otherwise {
        nextCharLen4 := ((8 + 24 + 24 + 32) >> 2).U
        nextData     := Cat(qspiReadCmdExp, io.in.paddr(23, 0), 0.U(24.W), 0.U(32.W))
        nextSOutLen4 := ((8 + 24) >> 2).U
      }

      when(nextCharLen4 === 0.U) {
        state := State.ready
      }.otherwise {
        shift.io.wen     := true.B
        shift.io.len4    := nextCharLen4
        shift.io.pIn     := nextData
        shift.io.sOutLen := nextSOutLen4
        state            := State.access
      }
    }

    is(State.access) {
      shift.io.go := true.B
      clgen.io.go := true.B
      ce          := true.B
      when(tipDone) {
        state := State.ready
      }
    }

    is(State.ready) {
      io.in.pready := true.B

      when(!isWriteReg) {
        val rd = shift.io.pOut(31, 0)
        io.in.prdata := Cat(rd(7, 0), rd(15, 8), rd(23, 16), rd(31, 24))
      }

      when(io.in.penable) {
        state := State.idle
      }
    }
  }

}
