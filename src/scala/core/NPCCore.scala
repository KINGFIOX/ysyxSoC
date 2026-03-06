package ysyx.core

import chisel3._
import chisel3.util._
import chisel3.probe.{define, Probe, ProbeValue, read}

import ysyx.core.common.{HasCSRParameter, HasCoreParameter, HasRegFileParameter}
import ysyx.core.component._
import freechips.rocketchip.amba.axi4._
import ysyx.CPUAXI4BundleParameters

/** Debug Bundle for difftest and tracing */
class DebugBundle extends Bundle with HasCoreParameter with HasRegFileParameter {
  val valid  = Bool()
  val pc     = UInt(XLEN.W)
  val dnpc   = UInt(XLEN.W)
  val inst   = UInt(InstLen.W)
  val isMMIO = Bool()
  val gpr    = Vec(NRReg, UInt(XLEN.W))
  val csr    = new CSRUDebugBundle
}

class NPCCore extends Module with HasCoreParameter with HasRegFileParameter with HasCSRParameter {
  val io = IO(new Bundle {
    val probe  = Output(Probe(new DebugBundle))
    val icache = AXI4Bundle(CPUAXI4BundleParameters())
    val dcache = AXI4Bundle(CPUAXI4BundleParameters())
  })

  // --- modules ---
  private val ifu = Module(new IFU)
  private val cu = Module(new CU)
  private val igu = Module(new IGU)
  private val rfu = Module(new RFU)
  private val csru = Module(new CSRU)
  private val alu = Module(new ALU)
  private val bru = Module(new BRU)
  private val lsu = Module(new LSU)
  private val excpu = Module(new EXCPU)

  // --- wire alias ---
  private val instW = ifu.io.out.bits.inst
  private val pcW = ifu.io.out.bits.pc
  private val snpcW = ifu.io.out.bits.pc + 4.U
  private val rdW = instW(11, 7)
  private val rs1W = instW(19, 15)
  private val rs2W = instW(24, 20)

  // --- connect: cu ---
  cu.io.in.inst := instW

  // --- connect: igu ---
  igu.io.in.inst_31_7 := instW(InstLen - 1, OpcodeLen)
  igu.io.in.immType := cu.io.out.immType
  private val immW = igu.io.out.imm

  // --- connect: rfu ---
  rfu.io.in.rs1_i := rs1W
  rfu.io.in.rs2_i := rs2W
  private val rs1DataW = rfu.io.out.rs1_v
  private val rs2DataW = rfu.io.out.rs2_v

  // --- connect: rfu ---
  private val csrRDataW = csru.io.rdata

  // --- connect: alu ---
  alu.io.in.op1 := MuxCase(
    0.U,
    Seq(
      (cu.io.out.aluSel1 === ALUOp1Sel.OP1_RS1) -> rs1DataW,
      (cu.io.out.aluSel1 === ALUOp1Sel.OP1_PC) -> pcW,
      (cu.io.out.aluSel1 === ALUOp1Sel.OP1_ZERO) -> 0.U
    )
  )
  alu.io.in.op2 := MuxCase(
    0.U,
    Seq(
      (cu.io.out.aluSel2 === ALUOp2Sel.OP2_RS2) -> rs2DataW,
      (cu.io.out.aluSel2 === ALUOp2Sel.OP2_IMM) -> immW
    )
  )
  alu.io.in.aluOp := cu.io.out.aluOp
  private val aluResultW = alu.io.out.result

  // --- connect: bru ---
  bru.io.in.rs1_v := rs1DataW
  bru.io.in.rs2_v := rs2DataW
  bru.io.in.op := cu.io.out.bruOp
  private val brTakenW = bru.io.out.br_flag

  private val dnpcW = MuxCase(
    snpcW,
    Seq(
      (cu.io.out.npcOp === NPCOpType.NPC_JAL) -> aluResultW,
      (cu.io.out.npcOp === NPCOpType.NPC_JALR) -> (aluResultW & (~1.U(XLEN.W))),
      (cu.io.out.npcOp === NPCOpType.NPC_BR && brTakenW) -> aluResultW,
      (cu.io.out.npcOp === NPCOpType.NPC_MRET) -> csru.io.xepc
    )
  )

  private val rdValW = MuxCase(0.U, Seq(
    (cu.io.out.wbSel === WBSel.WB_CSR) -> csrRDataW,
    (cu.io.out.wbSel === WBSel.WB_ALU) -> aluResultW,
    (cu.io.out.wbSel === WBSel.WB_PC4) -> snpcW,
  ))

  // --- connect: registers ---
  private val memAddrQ = RegInit(0.U(XLEN.W))
  private val memWdataQ = RegInit(0.U(XLEN.W))
  private val memOpQ = Reg(MemUOpType())
  private val memEnQ = RegInit(false.B)
  private val rdIdxQ = RegInit(0.U(NRRegbits.W))
  private val rdValQ = RegInit(0.U(XLEN.W))
  private val rfWenQ = RegInit(false.B)
  private val dnpcQ = RegInit(0.U(XLEN.W))
  private val pcQ = RegInit(0.U(XLEN.W))
  private val instQ = RegInit(0.U(InstLen.W))
  private val csrWenQ = RegInit(false.B)
  private val csrWdataQ = RegInit(0.U(XLEN.W))
  private val csrWopQ = Reg(CSROpType())
  private val csrWaddrQ = RegInit(0.U(XLEN.W))
  private val csrMcauseQ = RegInit(0.U(XLEN.W))
  private val csrMtvalQ = RegInit(0.U(XLEN.W))
  private val isMmioQ = RegInit(false.B)

  private val isMemW = cu.io.out.memEn

  object State extends ChiselEnum {
    val idle, ifu_valid_wait, writeback, exception, mem_ready_wait, mem_valid_wait, ifu_ready_wait = Value
  }
  private val stateQ = RegInit(State.ifu_valid_wait)

  private val cpiQ = RegInit(0.U(32.W)) //

  switch(stateQ) {

    // reset state
    // set this state along with the reset signal
    // because of the differential reset signal between cpu and SoC
    is(State.idle) {
      stateQ := State.ifu_valid_wait
    }

    is(State.ifu_valid_wait) {
      memEnQ := false.B // reset
      rfWenQ := false.B
      csrWenQ := false.B
      isMmioQ := false.B
      when(ifu.io.out.fire) {
        rfWenQ := cu.io.out.rfWen // latch
        rdIdxQ := rdW
        dnpcQ := dnpcW
        pcQ := pcW
        instQ := instW
        when(excpu.io.out.fire) {
          stateQ := State.exception
          csrMcauseQ := excpu.io.out.bits.mcause
          csrMtvalQ := excpu.io.out.bits.mtval
        } .elsewhen(isMemW) {
          stateQ := State.mem_ready_wait
          memOpQ := cu.io.out.memOp
          memEnQ := cu.io.out.memEn
          memAddrQ := aluResultW
          memWdataQ := rs2DataW
        } .otherwise {
          stateQ := State.writeback
          rdValQ := rdValW
          csrWenQ := cu.io.out.csrWen
          csrWdataQ := rs1DataW
          csrWopQ := cu.io.out.csrOp
          csrWaddrQ := immW
        }
      }
    }

    is(State.mem_ready_wait) {
      when(lsu.io.in.fire) {
        stateQ := State.mem_valid_wait
      }
    }

    is(State.mem_valid_wait) {
      when(lsu.io.out.fire) {
        isMmioQ := lsu.io.isMMIO
        when(excpu.io.out.fire) {
          stateQ := State.exception
          csrMcauseQ := excpu.io.out.bits.mcause
          csrMtvalQ := excpu.io.out.bits.mtval
        } .otherwise {
          stateQ := State.writeback
          rdValQ := lsu.io.out.bits.rdata
        }
      }
    }

    is(State.writeback) {
      stateQ := State.ifu_ready_wait
    }

    is(State.exception) {
      stateQ := State.ifu_ready_wait
      dnpcQ := csru.io.xtvec
    }

    is(State.ifu_ready_wait) {
      when(ifu.io.in.fire) {
        stateQ := State.ifu_valid_wait
      }
    }

  }

  ifu.io.out.ready := (stateQ === State.ifu_valid_wait)
  ifu.io.in.valid := (stateQ === State.ifu_ready_wait)
  ifu.io.in.bits.dnpc := dnpcQ
  ifu.io.icache <> io.icache

  rfu.io.in.wen := rfWenQ && (stateQ === State.writeback)
  rfu.io.in.wdata := rdValQ
  rfu.io.in.rd_i := rdIdxQ

  csru.io.addr := Mux(stateQ === State.ifu_valid_wait, immW, csrWaddrQ)
  csru.io.wdata := csrWdataQ
  csru.io.wen := csrWenQ && (stateQ === State.writeback)
  csru.io.wop := csrWopQ
  csru.io.commit.xcause := csrMcauseQ
  csru.io.commit.xcause_wen := (stateQ === State.exception)
  csru.io.commit.xepc := pcQ
  csru.io.commit.xepc_wen := (stateQ === State.exception)
  csru.io.commit.xtval := csrMtvalQ
  csru.io.commit.xtval_wen := (stateQ === State.exception)

  lsu.io.in.valid := (stateQ === State.mem_ready_wait)
  lsu.io.in.bits.op := memOpQ
  lsu.io.in.bits.en := memEnQ
  lsu.io.in.bits.addr := memAddrQ
  lsu.io.in.bits.wdata := memWdataQ
  lsu.io.out.ready := (stateQ === State.mem_valid_wait)
  lsu.io.dcache <> io.dcache

  excpu.io.in.bits.ifu := ifu.io.out.bits.exception
  excpu.io.in.bits.ifuEn := ifu.io.out.fire && ifu.io.out.bits.exceptionEn
  excpu.io.in.bits.ifuXtval := ifu.io.out.bits.xtval
  excpu.io.in.bits.cu := cu.io.out.exception
  excpu.io.in.bits.cuEn := ifu.io.out.fire && cu.io.out.exceptionEn
  excpu.io.in.bits.cuXtval := cu.io.out.xtval
  excpu.io.in.bits.lsu := lsu.io.out.bits.exception
  excpu.io.in.bits.lsuEn := lsu.io.out.fire && lsu.io.out.bits.exceptionEn
  excpu.io.in.bits.lsuXtval := memAddrQ
  excpu.io.in.bits.pc := Mux(stateQ === State.ifu_valid_wait, ifu.io.out.bits.pc, pcQ)
  excpu.io.in.valid := (stateQ === State.ifu_valid_wait) || (stateQ === State.mem_valid_wait)
  excpu.io.out.ready := (stateQ === State.ifu_valid_wait) || (stateQ === State.mem_valid_wait)

  /* ========== Debug Output (Probe) ========== */
  val debugBundle = Wire(new DebugBundle)
  debugBundle.valid  := ifu.io.in.fire
  debugBundle.pc     := pcQ
  debugBundle.dnpc   := dnpcQ
  debugBundle.inst   := instQ
  debugBundle.isMMIO := isMmioQ
  debugBundle.gpr    := read(rfu.io.probe)
  debugBundle.csr    := read(csru.io.probe)
  define(io.probe, ProbeValue(debugBundle))
}
