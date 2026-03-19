package ysyx.soc

import chisel3._
import chisel3.util._
import chisel3.experimental.Analog
import chisel3.util.circt.dpi._

import freechips.rocketchip.amba.axi4._
import org.chipsalliance.cde.config.Parameters
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.util._

// FIXME: sdram without dq: Analog(16.W)
class SDRAMIO_Dq extends Bundle {
  val clk = Output(Bool())
  val cke = Output(Bool())
  val cs = Output(Bool())
  val ras = Output(Bool())
  val cas = Output(Bool())
  val we = Output(Bool())
  val a = Output(UInt(13.W))
  val ba = Output(UInt(2.W))
  val dqm = Output(UInt(2.W))
  val dq = Analog(16.W)
}

class SDRAMIO extends Bundle {
  val clk = Output(Bool())
  val cke = Output(Bool())
  val cs = Output(Bool())
  val ras = Output(Bool())
  val cas = Output(Bool())
  val we = Output(Bool())
  val a = Output(UInt(13.W))
  val ba = Output(UInt(2.W))
  val dqm = Output(UInt(2.W))
}

// FIXME: sdram without dq: Analog(16.W)
class SdramCoreIO extends Bundle {
  val inportWr = Input(UInt(4.W)) // strb
  val inportRd = Input(Bool())
  val inportLen = Input(UInt(8.W)) // burst len = 0
  val inportAddr = Input(UInt(32.W))
  val inportWriteData = Input(UInt(32.W))

  val inportAccept = Output(Bool())
  val inportAck = Output(Bool())
  val inportError = Output(Bool())
  val inportReadData = Output(UInt(32.W))

  val sdram = new SDRAMIO
}

// fixed parameters for sdram
case class SdramParams() {
  val mhz = 50
  val addrW = 24
  val colW = 9
  val bankW = 2
  val dataW = 16
  val casLatency = 2
  val tRCD_ns = 20
  val tRP_ns = 20
  val tRFC_ns = 60
  val dqmW = dataW / 8
  val rowW = addrW - colW - bankW
  val banks = 1 << bankW
  val refreshCnt = 1 << rowW
  val startDelay = 100000 / (1000 / mhz)
  val refreshCycles = (64000 * mhz) / refreshCnt - 1
  val cycleTimeNs = 1000 / mhz
  val trcdCycles = (tRCD_ns + (cycleTimeNs - 1)) / cycleTimeNs
  val trpCycles = (tRP_ns + (cycleTimeNs - 1)) / cycleTimeNs
  val trfcCycles = (tRFC_ns + (cycleTimeNs - 1)) / cycleTimeNs
}

class SdramCore extends Module {
  private val p = SdramParams()

  val io = IO(new SdramCoreIO)

  // --- SDRAM command encodings ---
  private val CMD_W = 4
  private val CMD_INHIBIT = "b1111".U(CMD_W.W)
  private val CMD_NOP = "b0111".U(CMD_W.W)
  private val CMD_ACTIVE = "b0011".U(CMD_W.W)
  private val CMD_READ = "b0101".U(CMD_W.W)
  private val CMD_WRITE = "b0100".U(CMD_W.W)
  private val CMD_TERMINATE = "b0110".U(CMD_W.W)
  private val CMD_PRECHARGE = "b0010".U(CMD_W.W)
  private val CMD_REFRESH = "b0001".U(CMD_W.W)
  private val CMD_LOAD_MODE = "b0000".U(CMD_W.W)

  // Mode: Burst Length = 2 (sequential), CAS=2
  // {3'b000, 1'b0, 2'b00, 3'b010, 1'b0, 3'b001} = 13'h0021
  private val MODE_REG = "h0021".U(p.rowW.W)

  private val AUTO_PRECHARGE = 10
  private val ALL_BANKS = 10

  private val DELAY_W = 4
  private val REFRESH_CNT_W = log2Ceil(p.startDelay + 101) + 1

  // --- External interface aliases (RamIO) ---
  private val ramAddrW = io.inportAddr
  private val ramWrW = io.inportWr
  private val ramRdW = io.inportRd
  private val ramWriteDataW = io.inportWriteData
  private val ramReqW = ramWrW =/= 0.U || ramRdW

  // --- Address bit extraction ---
  private val addrColW =
    Cat(0.U((p.rowW - p.colW).W), ramAddrW(p.colW, 2), 0.U(1.W))
  private val addrRowW = ramAddrW(p.addrW, p.colW + 3)
  private val addrBankW = ramAddrW(p.colW + 2, p.colW + 1)

  // States
  object State extends ChiselEnum {
    //   0      1     2       3       4        5        6       7        8         9
    val init, delay, idle, activate, read, read_wait, write0, write1, precharge,
        refresh = Value
  }

  // --- state machine ---
  private val stateQ = RegInit(State.init)
  private val targetStateQ = RegInit(State.idle)
  private val delayStateQ = RegInit(State.idle)
  private val delayQ = RegInit(0.U(DELAY_W.W))

  // --- outputs: sdram ---
  private val commandW = WireInit(CMD_NOP);
  private val commandQ = RegNext(commandW, CMD_INHIBIT)
  io.sdram.cs := commandQ(3)
  io.sdram.ras := commandQ(2)
  io.sdram.cas := commandQ(1)
  io.sdram.we := commandQ(0)
  private val dqmW = WireInit("b11".U(p.dqmW.W)); val dqmQ = RegNext(dqmW);
  io.sdram.dqm := dqmQ
  private val addrQ = RegInit(0.U(p.rowW.W)); io.sdram.a := addrQ
  private val bankQ = RegInit(0.U(p.bankW.W)); io.sdram.ba := bankQ
  private val dataOutQ = RegInit(0.U(p.dataW.W));
  private val ckeQ = RegInit(false.B); io.sdram.cke := ckeQ
  // --- tri-state ---
  val sdramDq = IO(Analog(16.W))
  private val dataInW = TriStateInBuf(
    sdramDq,
    dataOutQ,
    RegNext(stateQ === State.write0 || stateQ === State.write1)
  )

  // --- outputs: bus ---
  io.inportAccept := (stateQ === State.read && ramRdW) ||
    (stateQ === State.write0 && (ramWrW =/= 0.U))
  io.inportError := false.B

  // --- latched request address (stable across state transitions) ---
  private val reqAddrQ = RegInit(0.U(32.W))
  private val reqWrStrbQ = RegInit("b1111".U(4.W))
  private val reqColW =
    Cat(0.U((p.rowW - p.colW).W), reqAddrQ(p.colW, 2), 0.U(1.W))
  private val reqRowW = reqAddrQ(p.addrW, p.colW + 3)
  private val reqBankW = reqAddrQ(p.colW + 2, p.colW + 1)

  // --- row open ---
  private val rowOpenQ = RegInit(0.U(p.banks.W))
  private val activeRowQ = RegInit(VecInit(Seq.fill(p.banks)(0.U(p.rowW.W))))
  private val rowHitW =
    rowOpenQ(addrBankW) && addrRowW === activeRowQ(addrBankW)

  // --- Periodic refresh (after init) ---
  private val (_, refreshTick) =
    Counter(stateQ =/= State.init, p.refreshCycles + 1)
  private val refreshQ = RegInit(false.B)
  when(refreshTick) {
    refreshQ := true.B
  }.elsewhen(stateQ === State.refresh) {
    refreshQ := false.B
  }

  // base(pos) = value
  private def withBit(base: UInt, pos: Int, value: Bool): UInt = {
    val w = base.getWidth
    val bits = Wire(Vec(w, Bool()))
    for (i <- 0 until w) {
      if (i == pos) bits(i) := value
      else bits(i) := base(i)
    }
    bits.asUInt
  }

  private def gotoDelay(dest: State.Type, cycles: Int): Unit = {
    stateQ := State.delay
    delayStateQ := dest
    delayQ := cycles.U
  }

  // --- State Machine ---
  switch(stateQ) {
    is(State.init) {
      commandW := CMD_NOP // default

      val initTimerQ =
        RegInit((p.startDelay + 100).U(REFRESH_CNT_W.W)) // init timer
      initTimerQ := initTimerQ - 1.U

      when(initTimerQ === 50.U) {
        ckeQ := true.B
      }.elsewhen(initTimerQ === 40.U) {
        commandW := CMD_PRECHARGE
        addrQ := withBit(0.U(p.rowW.W), ALL_BANKS, true.B)
      }.elsewhen(initTimerQ === 20.U || initTimerQ === 30.U) {
        commandW := CMD_REFRESH
      }.elsewhen(initTimerQ === 10.U) {
        commandW := CMD_LOAD_MODE
        addrQ := MODE_REG
      }.elsewhen(initTimerQ === 0.U) {
        commandW := CMD_NOP
        stateQ := State.idle
      }
    }

    is(State.idle) {
      when(refreshQ) { // refresh come first
        when(rowOpenQ.orR) { stateQ := State.precharge }
          .otherwise { stateQ := State.refresh }
        targetStateQ := State.refresh
      }.elsewhen(ramReqW) {
        reqAddrQ := ramAddrW
        reqWrStrbQ := ramWrW
        when(rowOpenQ(addrBankW) && addrRowW === activeRowQ(addrBankW)) {
          stateQ := Mux(ramRdW, State.read, State.write0)
        }.elsewhen(rowOpenQ(addrBankW)) {
          stateQ := State.precharge
          targetStateQ := Mux(ramRdW, State.read, State.write0)
        }.otherwise {
          stateQ := State.activate
          targetStateQ := Mux(ramRdW, State.read, State.write0)
        }
      }
    }

    is(State.activate) {
      gotoDelay(targetStateQ, p.trcdCycles) // read/write

      commandW := CMD_ACTIVE
      addrQ := reqRowW
      bankQ := reqBankW

      activeRowQ(reqBankW) := reqRowW
      rowOpenQ := rowOpenQ | (1.U << reqBankW)
    }

    is(State.read) {
      stateQ := State.read_wait

      commandW := CMD_READ
      addrQ := withBit(reqColW, AUTO_PRECHARGE, false.B)
      bankQ := reqBankW
      dqmW := 0.U
    }

    is(State.read_wait) {
      commandW := CMD_NOP

      gotoDelay(State.idle, p.casLatency) // default
      when(!refreshQ && ramReqW && ramRdW) { // burst from axi4
        when(rowHitW) {
          reqAddrQ := ramAddrW
          stateQ := State.read // renew state instead of delay
        }
      }
    }

    is(State.write0) {
      stateQ := State.write1

      commandW := CMD_WRITE
      addrQ := withBit(reqColW, AUTO_PRECHARGE, false.B)
      bankQ := reqBankW
      dataOutQ := ramWriteDataW(15, 0)
      dqmW := ~reqWrStrbQ(1, 0)
    }

    is(State.write1) {
      stateQ := State.idle
      when(!refreshQ && ramReqW && (ramWrW =/= 0.U)) {
        when(rowHitW) {
          reqAddrQ := ramAddrW
          reqWrStrbQ := ramWrW
          stateQ := State.write0
        }
      }

      dataOutQ := RegNext(ramWriteDataW(31, 16))
      // bankQ := bankQ
      addrQ := withBit(addrQ, AUTO_PRECHARGE, false.B)
      dqmW := ~reqWrStrbQ(3, 2)
    }

    is(State.precharge) {
      commandW := CMD_PRECHARGE
      when(targetStateQ === State.refresh) {
        gotoDelay(State.refresh, p.trpCycles)
        addrQ := withBit(0.U(p.rowW.W), ALL_BANKS, true.B)
        rowOpenQ := 0.U
      }.otherwise {
        gotoDelay(State.activate, p.trpCycles)
        addrQ := withBit(0.U(p.rowW.W), ALL_BANKS, false.B)
        bankQ := reqBankW
        rowOpenQ := rowOpenQ & ~(1.U << reqBankW)
      }
    }

    is(State.refresh) {
      gotoDelay(State.idle, p.trfcCycles)

      commandW := CMD_REFRESH
    }

    is(State.delay) {
      delayQ := delayQ - 1.U
      when(delayQ === 1.U) { stateQ := delayStateQ }
    }
  }

  // --- Read data pipeline ---
  private val sampleDataQ = ShiftRegister(dataInW, 2, 0.U(p.dataW.W), true.B)
  private val rdDelayed =
    ShiftRegister(stateQ === State.read, p.casLatency + 2, false.B, true.B)
  io.inportReadData := Cat(sampleDataQ, RegNext(sampleDataQ))
  io.inportAck := RegNext(stateQ === State.write1 || rdDelayed)

  io.sdram.clk := (~clock.asUInt)
}

class sdram_top_axi(
    axiParams: AXI4BundleParameters
) extends Module {
  val io = IO(new Bundle {
    val axi = Flipped(new AXI4Bundle(axiParams))
    val sdram = new SDRAMIO
  })
  private val pmem = Module(new SdramAxiPmem(axiParams))
  private val core = Module(new SdramCore)

  pmem.io.axi <> io.axi

  core.io.inportWr := pmem.io.ram.wstrb
  core.io.inportRd := pmem.io.ram.rd
  core.io.inportLen := pmem.io.ram.len
  core.io.inportAddr := pmem.io.ram.addr
  core.io.inportWriteData := pmem.io.ram.writeData
  pmem.io.ram.accept := core.io.inportAccept
  pmem.io.ram.ack := core.io.inportAck
  pmem.io.ram.error := core.io.inportError
  pmem.io.ram.readData := core.io.inportReadData

  io.sdram <> core.io.sdram
  val sdramDq = IO(Analog(16.W))
  core.sdramDq <> sdramDq
}

class RamIO extends Bundle {
  val wstrb = Output(UInt(4.W))
  val rd = Output(Bool())
  val len = Output(UInt(8.W))
  val addr = Output(UInt(32.W))
  val writeData = Output(UInt(32.W))
  val accept = Input(Bool())
  val ack = Input(Bool())
  val error = Input(Bool())
  val readData = Input(UInt(32.W))
}

class WDataEntry(dataBits: Int) extends Bundle {
  val data = UInt(dataBits.W)
  val strb = UInt((dataBits / 8).W)
}

class SdramAxiPmem(axiParams: AXI4BundleParameters) extends Module {
  val io = IO(new Bundle {
    val axi = Flipped(new AXI4Bundle(axiParams))
    val ram = new RamIO
  })

  private val QUEUE_DEPTH = 4

  private def calculateAddrNext(addr: UInt, axtype: UInt, axlen: UInt): UInt = {
    val result = WireDefault(addr + 4.U)
    val mask = WireDefault(0.U(32.W))
    switch(axtype) {
      is(0.U) { result := addr }
      is(2.U) {
        switch(axlen) {
          is(0.U) { mask := "h03".U }
          is(1.U) { mask := "h07".U }
          is(3.U) { mask := "h0F".U }
          is(7.U) { mask := "h1F".U }
          is(15.U) { mask := "h3F".U }
        }
        result := (addr & ~mask) | ((addr + 4.U) & mask)
      }
    }
    result
  }

  // ==================== Read FSM ====================
  object RState extends ChiselEnum {
    val rIdle, rBurst = Value
  }

  private val rStateQ = RegInit(RState.rIdle)
  private val rAddrQ = Reg(UInt(axiParams.addrBits.W))
  private val rIdQ = Reg(UInt(axiParams.idBits.W))
  private val rBurstTypeQ = Reg(UInt(axiParams.burstBits.W))
  private val rBurstLenQ = Reg(UInt(axiParams.lenBits.W))
  private val rReqCntQ = Reg(UInt(axiParams.lenBits.W))
  private val rRespCntQ = Reg(UInt(axiParams.lenBits.W))

  private val rDataQueue = Module(
    new Queue(UInt(axiParams.dataBits.W), QUEUE_DEPTH)
  )

  // ==================== Write FSM ====================
  object WState extends ChiselEnum {
    val wIdle, wBurst, wResp = Value
  }

  private val wStateQ = RegInit(WState.wIdle)
  private val wAddrQ = Reg(UInt(axiParams.addrBits.W))
  private val wIdQ = Reg(UInt(axiParams.idBits.W))
  private val wBurstTypeQ = Reg(UInt(axiParams.burstBits.W))
  private val wBurstLenQ = Reg(UInt(axiParams.lenBits.W))
  private val wReqCntQ = Reg(UInt(axiParams.lenBits.W))

  private val wDataQueue = Module(
    new Queue(new WDataEntry(axiParams.dataBits), QUEUE_DEPTH)
  )

  // ==================== Ack Pending & Flow Control ====================
  private val rAckPendingQ = RegInit(0.U(4.W))
  private val wAckPendingQ = RegInit(0.U(4.W))
  private val rOutstandingQ = RegInit(
    0.U(4.W)
  ) // core: accepted but not acknowledged

  // ==================== Arbiter ====================
  private val rAllReqsSentQ = RegInit(true.B)
  private val wAllReqsSentQ = RegInit(true.B)

  private val rReqRamW = rStateQ === RState.rBurst && // tip
    !rAllReqsSentQ &&
    rOutstandingQ < QUEUE_DEPTH.U

  private val wReqRamW = wStateQ === WState.wBurst &&
    !wAllReqsSentQ &&
    wDataQueue.io.deq.valid

  private val arbiter = Module(new RRArbiter(UInt(0.W), 2))
  arbiter.io.in(0).valid := rReqRamW && wAckPendingQ === 0.U
  arbiter.io.in(0).bits := DontCare
  arbiter.io.in(1).valid := wReqRamW && rAckPendingQ === 0.U
  arbiter.io.in(1).bits := DontCare
  arbiter.io.out.ready := io.ram.accept

  private val grantReadW = arbiter.io.out.valid && arbiter.io.chosen === 0.U
  private val grantWriteW = arbiter.io.out.valid && arbiter.io.chosen === 1.U

  // ==================== Ack Routing ====================
  private val ackToReadW = io.ram.ack && rAckPendingQ > 0.U
  private val ackToWriteW = io.ram.ack && wAckPendingQ > 0.U

  rAckPendingQ := rAckPendingQ + (grantReadW && io.ram.accept).asUInt - ackToReadW.asUInt
  wAckPendingQ := wAckPendingQ + (grantWriteW && io.ram.accept).asUInt - ackToWriteW.asUInt
  rOutstandingQ := rOutstandingQ + (grantReadW && io.ram.accept).asUInt - io.axi.r.fire.asUInt

  // ==================== RAM Interface Mux ====================
  io.ram.addr := MuxCase(
    0.U,
    Seq(
      grantReadW -> rAddrQ,
      grantWriteW -> wAddrQ
    )
  )
  io.ram.rd := grantReadW
  io.ram.wstrb := Mux(grantWriteW, wDataQueue.io.deq.bits.strb, 0.U)
  io.ram.writeData := wDataQueue.io.deq.bits.data
  io.ram.len := MuxCase(
    0.U,
    Seq(
      grantReadW -> rBurstLenQ,
      grantWriteW -> wBurstLenQ
    )
  )

  // ==================== Read Data Queue ====================
  rDataQueue.io.enq.valid := ackToReadW
  rDataQueue.io.enq.bits := io.ram.readData
  rDataQueue.io.deq.ready := io.axi.r.ready && rStateQ === RState.rBurst

  // ==================== Write Data Queue ====================
  wDataQueue.io.enq.valid := io.axi.w.valid && (wStateQ === WState.wBurst)
  wDataQueue.io.enq.bits.data := io.axi.w.bits.data
  wDataQueue.io.enq.bits.strb := io.axi.w.bits.strb
  wDataQueue.io.deq.ready := grantWriteW && io.ram.accept

  // ==================== AXI Read Response ====================
  io.axi.r.valid := rDataQueue.io.deq.valid && rStateQ === RState.rBurst
  io.axi.r.bits.data := rDataQueue.io.deq.bits
  io.axi.r.bits.resp := 0.U
  io.axi.r.bits.id := rIdQ // same id in one burst
  io.axi.r.bits.last := rRespCntQ === 0.U

  // ==================== AXI Write/Other Defaults ====================
  io.axi.ar.ready := rStateQ === RState.rIdle
  io.axi.aw.ready := wStateQ === WState.wIdle
  io.axi.w.ready := false.B
  io.axi.b.valid := false.B
  io.axi.b.bits.resp := 0.U
  io.axi.b.bits.id := wIdQ

  // ==================== Read FSM Logic ====================
  switch(rStateQ) {

    is(RState.rIdle) {
      when(io.axi.ar.fire) {
        rAddrQ := io.axi.ar.bits.addr // latch
        rIdQ := io.axi.ar.bits.id
        rBurstTypeQ := io.axi.ar.bits.burst
        rBurstLenQ := io.axi.ar.bits.len
        rReqCntQ := io.axi.ar.bits.len // counter
        rRespCntQ := io.axi.ar.bits.len
        rAllReqsSentQ := false.B
        rStateQ := RState.rBurst
      }
    }

    is(RState.rBurst) {
      when(grantReadW && io.ram.accept) { // pop from queue
        when(rReqCntQ === 0.U) {
          rAllReqsSentQ := true.B
        }.otherwise {
          rReqCntQ := rReqCntQ - 1.U
          rAddrQ := calculateAddrNext(rAddrQ, rBurstTypeQ, rBurstLenQ)
        }
      }

      when(io.axi.r.fire) {
        when(rRespCntQ === 0.U) {
          rStateQ := RState.rIdle
        }.otherwise {
          rRespCntQ := rRespCntQ - 1.U
        }
      }
    }

  }

  // ==================== Write FSM Logic ====================
  switch(wStateQ) {

    is(WState.wIdle) {
      when(io.axi.aw.fire) {
        wAddrQ := io.axi.aw.bits.addr // latch
        wIdQ := io.axi.aw.bits.id
        wBurstTypeQ := io.axi.aw.bits.burst
        wBurstLenQ := io.axi.aw.bits.len // counter
        wReqCntQ := io.axi.aw.bits.len
        wAllReqsSentQ := false.B
        wStateQ := WState.wBurst
      }
    }

    is(WState.wBurst) {
      io.axi.w.ready := wDataQueue.io.enq.ready

      when(wDataQueue.io.deq.fire) {
        when(wReqCntQ === 0.U) {
          wAllReqsSentQ := true.B
        }.otherwise {
          wReqCntQ := wReqCntQ - 1.U
          wAddrQ := calculateAddrNext(wAddrQ, wBurstTypeQ, wBurstLenQ)
        }
      }

      when(wAllReqsSentQ && wAckPendingQ === 0.U) {
        wStateQ := WState.wResp
      }
    }

    is(WState.wResp) {
      io.axi.b.valid := true.B
      when(io.axi.b.fire) {
        wStateQ := WState.wIdle
      }
    }

  }
}

class sdram_mem extends RawModule {
  val io = IO(Flipped(new SDRAMIO_Dq))
  val clock = (~io.clk.asBool).asClock
  val reset = (io.cs).asAsyncReset
  val module = withClockAndReset(clock, reset) { Module(new SdramMemImpl) }
  module.io.cke_n := io.cke
  module.io.ras_n := io.ras
  module.io.cas_n := io.cas
  module.io.we_n := io.we
  module.io.addr := io.a
  module.io.ba := io.ba
  module.io.dqm_n := io.dqm
  module.io.data_input := TriStateInBuf(
    io.dq,
    module.io.data_output,
    module.io.data_out_en
  )
}

class AXI4SDRAM(address: Seq[AddressSet])(implicit p: Parameters)
    extends LazyModule {
  val beatBytes = 4
  val node = AXI4SlaveNode(
    Seq(
      AXI4SlavePortParameters(
        Seq(
          AXI4SlaveParameters(
            address = address,
            executable = true,
            supportsWrite = TransferSizes(1, beatBytes),
            supportsRead = TransferSizes(1, beatBytes),
            interleavedId = Some(0)
          )
        ),
        beatBytes = beatBytes
      )
    )
  )

  lazy val module = new Impl
  class Impl extends LazyModuleImp(this) {
    val (in, edgeIn) = node.in(0)
    val sdram_bundle = IO(new SDRAMIO_Dq)

    val params = edgeIn.bundle
    val msdram = Module(new sdram_top_axi(params))
    msdram.io.axi <> in

    // sdram_bundle <> msdram.io.sdram
    // FIXME: fuck chisel, can't connect bundle using operator `<>` with Analog within it.
    sdram_bundle.clk := msdram.io.sdram.clk
    sdram_bundle.cke := msdram.io.sdram.cke
    sdram_bundle.cs := msdram.io.sdram.cs
    sdram_bundle.ras := msdram.io.sdram.ras
    sdram_bundle.cas := msdram.io.sdram.cas
    sdram_bundle.we := msdram.io.sdram.we
    sdram_bundle.a := msdram.io.sdram.a
    sdram_bundle.ba := msdram.io.sdram.ba
    sdram_bundle.dqm := msdram.io.sdram.dqm
    sdram_bundle.dq <> msdram.sdramDq
  }
}

class SdramMemImpl extends Module with RequireAsyncReset {
  private val WIDTH_BANK = 2
  private val WIDTH_COLS = 9
  private val WIDTH_ROWS = 13
  private val NUM_BANKS = 1 << WIDTH_BANK
  private val NUM_ROWS = 1 << WIDTH_ROWS

  val io = IO(new Bundle {
    val ras_n = Input(Bool())
    val cke_n = Input(Bool())
    val cas_n = Input(Bool())
    val we_n = Input(Bool())
    val addr = Input(UInt(WIDTH_ROWS.W))
    val ba = Input(UInt(WIDTH_BANK.W))
    val dqm_n = Input(UInt(2.W))
    val data_output = Output(UInt(16.W))
    val data_out_en = Output(Bool())
    val data_input = Input(UInt(16.W))
  })

  // --- settings ---
  private val writeBurstEnQ = RegInit(false.B)
  private val burstLenQ = RegInit(0.U(3.W))

  // --- states ---
  private val activeRowQ = RegInit(
    VecInit(Seq.fill(NUM_BANKS)(0.U(WIDTH_ROWS.W)))
  )
  private val activeEnRowQ = RegInit(VecInit(Seq.fill(NUM_BANKS)(false.B)))
  private val burstReadCountQ = RegInit(0.U(3.W))
  private val burstWriteCountQ = RegInit(0.U(3.W))
  private val burstAddrQ = RegInit(0.U(32.W))

  // --- modules ---
  private val sdram_cmd = Module(new sdram_cmd)
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
    val nop, active, read, write, burst_terminate, precharge, refresh,
        load_mode = Value
  }
  private val commandW = MuxCase(
    Command.nop,
    Seq(
      (io.ras_n && io.cas_n && io.we_n) -> Command.nop, // 111 (7)
      (!io.ras_n && io.cas_n && io.we_n) -> Command.active, // 011 (3)
      (io.ras_n && !io.cas_n && io.we_n) -> Command.read, // 101 (5)
      (io.ras_n && !io.cas_n && !io.we_n) -> Command.write, // 100 (4)
      (io.ras_n && io.cas_n && !io.we_n) -> Command.burst_terminate, // 110 (6)
      (!io.ras_n && io.cas_n && !io.we_n) -> Command.precharge, // 010 (2)
      (!io.ras_n && !io.cas_n && io.we_n) -> Command.refresh, // 001 (1)
      (!io.ras_n && !io.cas_n && !io.we_n) -> Command.load_mode // 000 (0)
    )
  )
  switch(commandW) {
    is(Command.load_mode) {
      writeBurstEnQ := !io.addr(9)
      burstLenQ := io.addr(2, 0)
    }
    is(Command.refresh) {
      for (i <- 0 until NUM_BANKS) {
        assert(activeEnRowQ(i) === false.B, "no row should be active")
      }
    }
    is(Command.active) {
      val baW = io.ba
      val rowW = io.addr
      // ! activeEnRowQ(baW) || ( activeEnRowQ(baW) && ( activeRowQ(baW) === rowW )
      assert(
        !activeEnRowQ(baW) || (activeRowQ(baW) === rowW),
        "row should not be active or should be the same row"
      )

      activeRowQ(baW) := rowW
      activeEnRowQ(baW) := true.B
    }
    is(Command.read) {
      val baW = io.ba
      assert(activeEnRowQ(baW), "row should be active")
      val rowW = activeRowQ(baW)
      val colW = io.addr(WIDTH_COLS - 1, 0)
      val addrW = Cat(rowW, baW, colW, 0.U(1.W))

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
      val addrW = Cat(rowW, baW, colW, 0.U(1.W))
      val wdataW = io.data_input

      sdram_cmd.io.valid := true.B
      sdram_cmd.io.addr := addrW
      sdram_cmd.io.wen := true.B
      sdram_cmd.io.wdata := wdataW
      sdram_cmd.io.dqm_n := io.dqm_n

      burstWriteCountQ := Mux(writeBurstEnQ, (1.U << burstLenQ) - 1.U, 0.U)
      burstAddrQ := addrW + 2.U
    }
    is(Command.nop) {
      when(burstReadCountQ > 0.U) {
        val addrW = burstAddrQ

        sdram_cmd.io.valid := true.B
        sdram_cmd.io.addr := addrW

        next_data_out_en := true.B

        burstReadCountQ := burstReadCountQ - 1.U
        burstAddrQ := addrW + 2.U
      }
      when(burstWriteCountQ > 0.U) {
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
      when(all_banks) {
        for (i <- 0 until NUM_BANKS) {
          activeEnRowQ(i) := false.B
        }
      }.otherwise {
        val baW = io.ba
        activeEnRowQ(baW) := false.B
      }
    }
  }
}

class sdram_cmd extends Module {
  val io = IO(new Bundle {
    val valid = Input(Bool())
    val wen = Input(Bool())
    val dqm_n = Input(UInt(2.W))
    val addr = Input(UInt(32.W))
    val wdata = Input(UInt(16.W))
    val rdata = Output(UInt(16.W))
  })

  io.rdata := RawClockedNonVoidFunctionCall(s"sdram_read", UInt(16.W))(
    clock,
    io.valid && !io.wen,
    io.addr
  )

  RawClockedVoidFunctionCall(s"sdram_write")(
    clock,
    io.valid && io.wen && !io.dqm_n(0),
    io.addr,
    io.wdata(7, 0)
  )

  RawClockedVoidFunctionCall(s"sdram_write")(
    clock,
    io.valid && io.wen && !io.dqm_n(1),
    io.addr + 1.U,
    io.wdata(15, 8)
  )

}
