package ysyx

import chisel3._
import chisel3.util._
import chisel3.experimental.Analog

import freechips.rocketchip.amba.axi4._
import freechips.rocketchip.amba.apb._
import org.chipsalliance.cde.config.Parameters
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.util._

class SDRAMIO extends Bundle {
  val clk = Output(Bool())
  val cke = Output(Bool())
  val cs  = Output(Bool())
  val ras = Output(Bool())
  val cas = Output(Bool())
  val we  = Output(Bool())
  val a   = Output(UInt(13.W))
  val ba  = Output(UInt(2.W))
  val dqm = Output(UInt(2.W))
  val dq  = Analog(16.W)
}

class sdram_top_axi extends BlackBox {
  val io = IO(new Bundle {
    val clock = Input(Clock())
    val reset = Input(Bool())
    val in = Flipped(new AXI4Bundle(AXI4BundleParameters(addrBits = 32, dataBits = 32, idBits = 4)))
    val sdram = new SDRAMIO
  })
}

class sdram_top_apb extends BlackBox {
  val io = IO(new Bundle {
    val clock = Input(Clock())
    val reset = Input(Bool())
    val in = Flipped(new APBBundle(APBBundleParameters(addrBits = 32, dataBits = 32)))
    val sdram = new SDRAMIO
  })
}

class sdram_mem extends RawModule {
  val io = IO(Flipped(new SDRAMIO))
  val sdram_dq = IO(Analog(16.W))
  val clock = ( ~ io.clk.asBool ).asClock
  val reset = ( io.cs ).asAsyncReset
  val module = withClockAndReset(clock, reset) { Module(new SdramMemImpl) }
  module.io.cke_n := io.cke
  module.io.ras_n := io.ras
  module.io.cas_n := io.cas
  module.io.we_n  := io.we
  module.io.addr  := io.a
  module.io.ba    := io.ba
  module.io.dqm_n := io.dqm
  module.io.data_input := TriStateInBuf( sdram_dq, module.io.data_output, module.io.data_out_en )
}

class AXI4SDRAM(address: Seq[AddressSet])(implicit p: Parameters) extends LazyModule {
  val beatBytes = 4
  val node = AXI4SlaveNode(Seq(AXI4SlavePortParameters(
    Seq(AXI4SlaveParameters(
        address       = address,
        executable    = true,
        supportsWrite = TransferSizes(1, beatBytes),
        supportsRead  = TransferSizes(1, beatBytes),
        interleavedId = Some(0))
    ),
    beatBytes  = beatBytes)))

  lazy val module = new Impl
  class Impl extends LazyModuleImp(this) {
    val (in, _) = node.in(0)
    val sdram_bundle = IO(new SDRAMIO)

    val msdram = Module(new sdram_top_axi)
    msdram.io.clock := clock
    msdram.io.reset := reset.asBool
    msdram.io.in <> in
    sdram_bundle <> msdram.io.sdram
  }
}

class APBSDRAM(address: Seq[AddressSet])(implicit p: Parameters) extends LazyModule {
  val node = APBSlaveNode(Seq(APBSlavePortParameters(
    Seq(APBSlaveParameters(
      address       = address,
      executable    = true,
      supportsRead  = true,
      supportsWrite = true)),
    beatBytes  = 4)))

  lazy val module = new Impl
  class Impl extends LazyModuleImp(this) {
    val (in, _) = node.in(0)
    val sdram_bundle = IO(new SDRAMIO)

    val msdram = Module(new sdram_top_apb)
    msdram.io.clock := clock
    msdram.io.reset := reset.asBool
    msdram.io.in <> in
    sdram_bundle <> msdram.io.sdram
  }
}

class SdramMemImpl extends Module with RequireAsyncReset {
  private val WIDTH_BANK  = 2
  private val WIDTH_COLS  = 9
  private val WIDTH_ROWS  = 13
  private val NUM_BANKS   = 1 << WIDTH_BANK
  private val NUM_ROWS    = 1 << WIDTH_ROWS

  val io = IO(new Bundle {
    val ras_n       = Input(Bool())
    val cke_n       = Input(Bool())
    val cas_n       = Input(Bool())
    val we_n        = Input(Bool())
    val addr        = Input(UInt(WIDTH_ROWS.W))
    val ba          = Input(UInt(WIDTH_BANK.W))
    val dqm_n       = Input(UInt(2.W))
    val data_output = Output(UInt(16.W))
    val data_out_en = Output(Bool())
    val data_input  = Input(UInt(16.W))
  })

  // --- settings ---
  private val writeBurstEnQ  = RegInit(false.B)
  private val burstLenQ = RegInit(0.U(3.W))

  // --- states ---
  private val activeRowQ   = RegInit(VecInit(Seq.fill(NUM_BANKS)(0.U(WIDTH_ROWS.W))))
  private val activeEnRowQ = RegInit(VecInit(Seq.fill(NUM_BANKS)(false.B)))
  private val burstReadCountQ = RegInit(0.U(3.W))
  private val burstWriteCountQ = RegInit(0.U(3.W))
  private val burstAddrQ = RegInit(0.U(32.W))

  // --- modules ---
  private val sdram_cmd = Module(new sdram_cmd)
  sdram_cmd.io.clock := clock
  sdram_cmd.io.valid := false.B // default
  sdram_cmd.io.wen := false.B
  sdram_cmd.io.addr := 0.U
  sdram_cmd.io.wdata := 0.U
  sdram_cmd.io.dqm_n := "b11".U

  // --- output ---
  private val next_data_out_en = WireInit(false.B)
  private val data_out_en_reg = RegNext(next_data_out_en)
  io.data_out_en := data_out_en_reg
  io.data_output := sdram_cmd.io.rdata

  object Command extends ChiselEnum {
    val nop, active, read, write, burst_terminate, precharge, refresh, load_mode = Value
  }
  private val commandW = MuxCase(Command.nop, Seq(
    ( io.ras_n && io.cas_n && io.we_n ) -> Command.nop, // 111 (7)
    ( !io.ras_n && io.cas_n && io.we_n ) -> Command.active, // 011 (3)
    ( io.ras_n && !io.cas_n && io.we_n ) -> Command.read, // 101 (5)
    ( io.ras_n && !io.cas_n && !io.we_n ) -> Command.write, // 100 (4)
    ( io.ras_n && io.cas_n && !io.we_n ) -> Command.burst_terminate, // 110 (6)
    ( !io.ras_n && io.cas_n && !io.we_n ) -> Command.precharge, // 010 (2)
    ( !io.ras_n && !io.cas_n && io.we_n ) -> Command.refresh, // 001 (1)
    ( !io.ras_n && !io.cas_n && !io.we_n ) -> Command.load_mode, // 000 (0)
  ))
  switch(commandW) {
    is(Command.load_mode) {
      writeBurstEnQ  := ! io.addr(9)
      burstLenQ := io.addr(2, 0)
    }
    is(Command.refresh) {
      for (i <- 0 until NUM_BANKS) {
        assert( activeEnRowQ(i) === false.B, "no row should be active" )
      }
    }
    is(Command.active) {
      val baW = io.ba
      val rowW = io.addr
      // ! activeEnRowQ(baW) || ( activeEnRowQ(baW) && ( activeRowQ(baW) === rowW )
      assert( !activeEnRowQ(baW) || (activeRowQ(baW) === rowW) , "row should not be active or should be the same row")

      activeRowQ(baW) := rowW
      activeEnRowQ(baW) := true.B
    }
    is(Command.read) {
      val baW = io.ba
      assert(activeEnRowQ(baW), "row should be active")
      val rowW = activeRowQ(baW)
      val colW = io.addr(WIDTH_COLS - 1, 0)
      val addrW = Cat( rowW, baW, colW, 0.U(1.W) )

      sdram_cmd.io.valid := true.B
      sdram_cmd.io.addr := addrW

      next_data_out_en := true.B

      burstReadCountQ := (1.U << burstLenQ) - 1.U
      burstAddrQ := addrW + 2.U
    }
    is(Command.write) {
      val baW = io.ba
      assert(activeEnRowQ(baW), "row should be active")
      val rowW = activeRowQ(baW)
      val colW = io.addr(WIDTH_COLS - 1, 0)
      val addrW = Cat( rowW, baW, colW, 0.U(1.W) )
      val wdataW = io.data_input

      sdram_cmd.io.valid := true.B
      sdram_cmd.io.addr := addrW
      sdram_cmd.io.wen := true.B
      sdram_cmd.io.wdata := wdataW
      sdram_cmd.io.dqm_n := io.dqm_n

      burstWriteCountQ := Mux( writeBurstEnQ, (1.U << burstLenQ) - 1.U, 0.U )
      burstAddrQ := addrW + 2.U
    }
    is(Command.nop) {
      when( burstReadCountQ > 0.U ) {
        val addrW = burstAddrQ

        sdram_cmd.io.valid := true.B
        sdram_cmd.io.addr := addrW

        next_data_out_en := true.B

        burstReadCountQ := burstReadCountQ - 1.U
        burstAddrQ := addrW + 2.U
      }
      when( burstWriteCountQ > 0.U ) {
        val addrW = burstAddrQ
        val wdataW = io.data_input

        sdram_cmd.io.valid := true.B
        sdram_cmd.io.addr := addrW
        sdram_cmd.io.wen := true.B
        sdram_cmd.io.wdata := wdataW
        sdram_cmd.io.dqm_n := io.dqm_n

        burstWriteCountQ := burstWriteCountQ - 1.U
        burstAddrQ := addrW + 2.U
      }
    }
    is(Command.burst_terminate) {
      burstReadCountQ := 0.U
      burstWriteCountQ := 0.U
    }
    is(Command.precharge) {
      val all_banks = io.addr(10)
      when( all_banks ) {
        for (i <- 0 until NUM_BANKS) {
          activeEnRowQ(i) := false.B
        }
      } .otherwise {
        val baW = io.ba
        activeEnRowQ(baW) := false.B
      }
    }
  }
}

class sdram_cmd_io extends Bundle {
  val clock = Input(Clock())
  val valid = Input(Bool())
  val wen = Input(Bool())
  val dqm_n = Input(UInt(2.W))
  val addr = Input(UInt(32.W))
  val wdata = Input(UInt(16.W))
  val rdata = Output(UInt(16.W))
}

class sdram_cmd extends FixedIOExtModule(new sdram_cmd_io) { }
