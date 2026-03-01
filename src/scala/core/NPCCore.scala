package ysyx.core

import chisel3._
import chisel3.util._
import chisel3.probe.{define, Probe, ProbeValue}

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
    val step   = Input(Bool())
    val probe  = Output(Probe(new DebugBundle))
    val icache = AXI4Bundle(CPUAXI4BundleParameters())
    val dcache = AXI4Bundle(CPUAXI4BundleParameters())
  })

  private val ifu = Module(new IFU)
  private val cu = Module(new CU)
  private val igu = Module(new IGU)
  private val rfu = Module(new RFU)
  private val csru = Module(new CSRU)
  private val alu = Module(new ALU)
  private val bru = Module(new BRU)
  private val lsu = Module(new LSU)
  private val excpu = Module(new EXCPU)

  private val inst = ifu.io.out.bits.inst
  private val pc = ifu.io.out.bits.pc
  private val snpc = ifu.io.out.bits.pc + 4.U
  private val rd = inst(11, 7)
  private val rs1 = inst(19, 15)
  private val rs2 = inst(24, 20)

  cu.io.in.inst := inst

  igu.io.in.inst_31_7 := inst(InstLen - 1, OpcodeLen)
  igu.io.in.immType := cu.io.out.immType
  private val imm = igu.io.out.imm

  rfu.io.in.rs1_i := rs1
  rfu.io.in.rs2_i := rs2
  private val rs1Data = rfu.io.out.rs1_v
  private val rs2Data = rfu.io.out.rs2_v

  private val csr_read = csru.io.rdata

  alu.io.in.op1 := MuxCase(
    0.U,
    Seq(
      (cu.io.out.aluSel1 === ALUOp1Sel.OP1_RS1) -> rs1Data,
      (cu.io.out.aluSel1 === ALUOp1Sel.OP1_PC) -> pc,
      (cu.io.out.aluSel1 === ALUOp1Sel.OP1_ZERO) -> 0.U
    )
  )
  alu.io.in.op2 := MuxCase(
    0.U,
    Seq(
      (cu.io.out.aluSel2 === ALUOp2Sel.OP2_RS2) -> rs2Data,
      (cu.io.out.aluSel2 === ALUOp2Sel.OP2_IMM) -> imm
    )
  )
  alu.io.in.aluOp := cu.io.out.aluOp
  private val aluResult = alu.io.out.result

  bru.io.in.rs1_v := rs1Data
  bru.io.in.rs2_v := rs2Data
  bru.io.in.op := cu.io.out.bruOp
  private val brTaken = bru.io.out.br_flag

  private val dnpc = MuxCase(
    snpc,
    Seq(
      (cu.io.out.npcOp === NPCOpType.NPC_JAL) -> aluResult,
      (cu.io.out.npcOp === NPCOpType.NPC_JALR) -> (aluResult & (~1.U(XLEN.W))),
      (cu.io.out.npcOp === NPCOpType.NPC_BR && brTaken) -> aluResult,
      (cu.io.out.npcOp === NPCOpType.NPC_MRET) -> csru.io.xepc
    )
  )

  private val rd_v = MuxCase(0.U, Seq(
    (cu.io.out.wbSel === WBSel.WB_CSR) -> csr_read,
    (cu.io.out.wbSel === WBSel.WB_ALU) -> aluResult,
    (cu.io.out.wbSel === WBSel.WB_PC4) -> snpc,
  ))

  private val mem_addr_reg = RegInit(0.U(XLEN.W))
  private val mem_wdata_reg = RegInit(0.U(XLEN.W))
  private val mem_op_reg = Reg(MemUOpType())
  private val mem_en_reg = RegInit(false.B)
  private val rd_i_reg = RegInit(0.U(NRRegbits.W))
  private val rd_v_reg = RegInit(0.U(XLEN.W))
  private val rf_wen_reg = RegInit(false.B)
  private val dnpc_reg = RegInit(0.U(XLEN.W))
  private val pc_reg = RegInit(0.U(XLEN.W))
  private val inst_reg = RegInit(0.U(InstLen.W))
  private val csr_wen_reg = RegInit(false.B)
  private val csr_wdata_reg = RegInit(0.U(XLEN.W))
  private val csr_wop_reg = Reg(CSROpType())
  private val csr_waddr_reg = RegInit(0.U(XLEN.W))
  private val csr_xcuase_reg = RegInit(0.U(XLEN.W))
  private val csr_xtval_reg = RegInit(0.U(XLEN.W))
  private val isMMIO_reg = RegInit(false.B)

  private val isMem = cu.io.out.memEn

  object State extends ChiselEnum {
    val idle, ifu_valid_wait, writeback, exception, mem_ready_wait, mem_valid_wait, ifu_ready_wait = Value
  }
  private val state = RegInit(State.idle)
  
  // step signal from external control
  private val step = io.step
  
  switch(state) {
    is(State.idle) {
      when(step) {
        state := State.ifu_valid_wait
        mem_en_reg := false.B
        rf_wen_reg := false.B
        csr_wen_reg := false.B
        isMMIO_reg := false.B
      }
    }
    is(State.ifu_valid_wait) {
      when(ifu.io.out.fire) {
        rf_wen_reg := cu.io.out.rfWen
        rd_i_reg := rd
        dnpc_reg := dnpc
        pc_reg := pc
        inst_reg := inst
        when(excpu.io.out.fire) {
          state := State.exception
          csr_xcuase_reg := excpu.io.out.bits.mcause
          csr_xtval_reg := excpu.io.out.bits.mtval
        } .elsewhen(isMem) {
          state := State.mem_ready_wait
          mem_op_reg := cu.io.out.memOp
          mem_en_reg := cu.io.out.memEn
          mem_addr_reg := aluResult
          mem_wdata_reg := rs2Data
        } .otherwise {
          state := State.writeback
          rd_v_reg := rd_v
          csr_wen_reg := cu.io.out.csrWen
          csr_wdata_reg := rs1Data
          csr_wop_reg := cu.io.out.csrOp
          csr_waddr_reg := imm
        }
      }
    }
    is(State.mem_ready_wait) {
      when(lsu.io.in.fire) {
        state := State.mem_valid_wait
      }
    }
    is(State.mem_valid_wait) {
      when(lsu.io.out.fire) {
        isMMIO_reg := lsu.io.isMMIO
        when(excpu.io.out.fire) {
          state := State.exception
          csr_xcuase_reg := excpu.io.out.bits.mcause
          csr_xtval_reg := excpu.io.out.bits.mtval
        } .otherwise {
          state := State.writeback
          rd_v_reg := lsu.io.out.bits.rdata
        }
      }
    }
    is(State.writeback) {
      state := State.ifu_ready_wait
    }
    is(State.exception) {
      state := State.ifu_ready_wait
      dnpc_reg := csru.io.xtvec
    }
    is(State.ifu_ready_wait) {
      when(ifu.io.in.fire) {
        state := State.idle
      }
    }
  }

  ifu.io.out.ready := (state === State.ifu_valid_wait)
  ifu.io.in.valid := (state === State.ifu_ready_wait)
  ifu.io.in.bits.dnpc := dnpc_reg
  ifu.io.icache <> io.icache
  ifu.io.step := step

  rfu.io.in.wen := rf_wen_reg && (state === State.writeback)
  rfu.io.in.wdata := rd_v_reg
  rfu.io.in.rd_i := rd_i_reg

  csru.io.addr := Mux(state === State.ifu_valid_wait, imm, csr_waddr_reg)
  csru.io.wdata := csr_wdata_reg
  csru.io.wen := csr_wen_reg && (state === State.writeback)
  csru.io.wop := csr_wop_reg
  csru.io.commit.xcause := csr_xcuase_reg
  csru.io.commit.xcause_wen := (state === State.exception)
  csru.io.commit.xepc := pc_reg
  csru.io.commit.xepc_wen := (state === State.exception)
  csru.io.commit.xtval := csr_xtval_reg
  csru.io.commit.xtval_wen := (state === State.exception)

  lsu.io.in.valid := (state === State.mem_ready_wait)
  lsu.io.in.bits.op := mem_op_reg
  lsu.io.in.bits.en := mem_en_reg
  lsu.io.in.bits.addr := mem_addr_reg
  lsu.io.in.bits.wdata := mem_wdata_reg
  lsu.io.out.ready := (state === State.mem_valid_wait)
  lsu.io.dcache <> io.dcache

  excpu.io.in.bits.ifu := ifu.io.out.bits.exception
  excpu.io.in.bits.ifuEn := ifu.io.out.fire && ifu.io.out.bits.exceptionEn
  excpu.io.in.bits.ifuXtval := ifu.io.out.bits.xtval
  excpu.io.in.bits.cu := cu.io.out.exception
  excpu.io.in.bits.cuEn := ifu.io.out.fire && cu.io.out.exceptionEn
  excpu.io.in.bits.cuXtval := cu.io.out.xtval
  excpu.io.in.bits.lsu := lsu.io.out.bits.exception
  excpu.io.in.bits.lsuEn := lsu.io.out.fire && lsu.io.out.bits.exceptionEn
  excpu.io.in.bits.lsuXtval := mem_addr_reg
  excpu.io.in.bits.pc := Mux(state === State.ifu_valid_wait, ifu.io.out.bits.pc, pc_reg)
  excpu.io.in.bits.a0 := rfu.io.out.debug.gpr(10)
  excpu.io.in.valid := (state === State.ifu_valid_wait) || (state === State.mem_valid_wait)
  excpu.io.out.ready := (state === State.ifu_valid_wait) || (state === State.mem_valid_wait)

  /* ========== Debug Output (Probe) ========== */
  val debugWire = Wire(new DebugBundle)
  debugWire.valid  := ifu.io.in.fire
  debugWire.pc     := pc_reg
  debugWire.dnpc   := dnpc_reg
  debugWire.inst   := inst_reg
  debugWire.isMMIO := isMMIO_reg
  debugWire.gpr    := rfu.io.out.debug.gpr
  debugWire.csr    := csru.io.debug
  define(io.probe, ProbeValue(debugWire))
}
