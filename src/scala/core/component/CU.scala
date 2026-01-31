/** @brief
  *   CU - 控制单元 (Control Unit) 根据指令生成各个模块的控制信号
  */

package ysyx.core.component

import chisel3._
import chisel3.util._
import ysyx.core.common.HasCoreParameter
import ysyx.core.common.HasRegFileParameter
import ysyx.core.common.Instructions._

/** ALU 操作数1 选择 */
object ALUOp1Sel extends ChiselEnum {
  val OP1_RS1, OP1_PC, OP1_ZERO = Value
}

/** ALU 操作数2 选择 */
object ALUOp2Sel extends ChiselEnum {
  val OP2_RS2, OP2_IMM = Value
}

/** 写回数据选择 */
object WBSel extends ChiselEnum {
  val WB_ALU, WB_MEM, WB_PC4, WB_CSR = Value
}

/** NPC 选择 (下一条 PC) */
object NPCOpType extends ChiselEnum {
  val NPC_4, NPC_BR, NPC_JAL, NPC_JALR, NPC_MRET = Value
}

/** CSR 操作类型 */
object CSROpType extends ChiselEnum {
  val CSR_RW, CSR_RS = Value // NOP, Read-Write, Read-Set
}

// mcause:
// 2. illegal instruction
// 3. breakpoint
// 8. ecall from U-mode
// 9. ecall from S-mode
// 11. ecall from M-mode
object CUExceptionType extends ChiselEnum {
  val cu_ILLEGAL_INSTRUCTION,
    cu_BREAKPOINT,
    cu_ECALL_FROM_U_MODE, // TODO: 暂时只有 M-mode 的 ecall
    cu_ECALL_FROM_S_MODE,
    cu_ECALL_FROM_M_MODE
    = Value
}

/** CU 输出的控制信号 */
class CUOutputBundle extends Bundle with HasRegFileParameter {
  val aluOp = ALUOpType(); val aluSel1 = ALUOp1Sel(); val aluSel2 = ALUOp2Sel() // ALU 控制
  val immType = ImmType()
  val npcOp = NPCOpType()
  val bruOp = BRUOpType() // bru

  val memOp = MemUOpType(); val memEn = Bool() // mem
  val wbSel = WBSel(); val rfWen = Bool() // write back
  val csrOp = CSROpType(); val csrWen = Bool() // csr

  // exception
  val exception = CUExceptionType()
  val exceptionEn = Bool()
  val xtval = Bool()
}

class CUInputBundle extends Bundle with HasCoreParameter {
  val inst = UInt(InstLen.W)
}

class CU extends Module with HasCoreParameter with HasRegFileParameter {
  val io = IO(new Bundle {
    val in  = Flipped(new CUInputBundle)
    val out = new CUOutputBundle
  })

  private val inst = io.in.inst

  /* ---------- 默认值 ---------- */
  io.out.aluOp   := WireDefault(ALUOpType.alu_X); io.out.aluSel1 := DontCare; io.out.aluSel2 := DontCare
  io.out.bruOp := WireDefault(BRUOpType.bru_X);
  io.out.immType := DontCare
  io.out.npcOp := NPCOpType.NPC_4 // 默认 pc + 4

  io.out.memOp := DontCare; io.out.memEn := WireDefault(false.B)
  io.out.wbSel := DontCare; io.out.rfWen := WireDefault(false.B)
  io.out.csrOp := DontCare; io.out.csrWen := WireDefault(false.B)

  private val isEbreak    = WireDefault(false.B)
  private val isEcall     = WireDefault(false.B)
  private val invalidInst = WireDefault(true.B)
  private val exceptionMapping = Seq(
    isEbreak    -> CUExceptionType.cu_BREAKPOINT,
    isEcall     -> CUExceptionType.cu_ECALL_FROM_M_MODE,
    invalidInst -> CUExceptionType.cu_ILLEGAL_INSTRUCTION
  )
  io.out.exception := MuxCase(DontCare, exceptionMapping)
  io.out.exceptionEn := isEbreak || isEcall || invalidInst
  io.out.xtval := 0.U

  /* ---------- R-type: add rd, rs1, rs2 ---------- */
  private def rInst(op: ALUOpType.Type): Unit /*无返回值*/ = {
    io.out.aluOp       := op
    io.out.aluSel1     := ALUOp1Sel.OP1_RS1
    io.out.aluSel2     := ALUOp2Sel.OP2_RS2
    io.out.wbSel       := WBSel.WB_ALU
    io.out.rfWen       := true.B
    invalidInst := false.B
  }
  when(inst === ADD) { rInst(ALUOpType.alu_ADD) }
  when(inst === SUB) { rInst(ALUOpType.alu_SUB) }
  when(inst === AND) { rInst(ALUOpType.alu_AND) }
  when(inst === OR) { rInst(ALUOpType.alu_OR) }
  when(inst === XOR) { rInst(ALUOpType.alu_XOR) }
  when(inst === SLL) { rInst(ALUOpType.alu_SLL) }
  when(inst === SRL) { rInst(ALUOpType.alu_SRL) }
  when(inst === SRA) { rInst(ALUOpType.alu_SRA) }
  when(inst === SLT) { rInst(ALUOpType.alu_SLT) }
  when(inst === SLTU) { rInst(ALUOpType.alu_SLTU) }

  /* ---------- I-type: addi rd, rs1, imm ---------- */
  private def iInst(op: ALUOpType.Type): Unit /*无返回值*/ = {
    io.out.aluOp       := op
    io.out.aluSel1     := ALUOp1Sel.OP1_RS1
    io.out.aluSel2     := ALUOp2Sel.OP2_IMM
    io.out.immType     := ImmType.IMM_I
    io.out.wbSel       := WBSel.WB_ALU
    io.out.rfWen       := true.B
    invalidInst := false.B
  }
  when(inst === ADDI) { iInst(ALUOpType.alu_ADD) }
  when(inst === ANDI) { iInst(ALUOpType.alu_AND) }
  when(inst === ORI) { iInst(ALUOpType.alu_OR) }
  when(inst === XORI) { iInst(ALUOpType.alu_XOR) }
  when(inst === SLLI) { iInst(ALUOpType.alu_SLL) }
  when(inst === SRLI) { iInst(ALUOpType.alu_SRL) }
  when(inst === SRAI) { iInst(ALUOpType.alu_SRA) }
  when(inst === SLTI) { iInst(ALUOpType.alu_SLT) }
  when(inst === SLTIU) { iInst(ALUOpType.alu_SLTU) }

  /* ---------- Load: lw rd, offset(rs1) ---------- */
  private def lInst(op: MemUOpType.Type): Unit /*无返回值*/ = {
    io.out.aluOp       := ALUOpType.alu_ADD
    io.out.aluSel1     := ALUOp1Sel.OP1_RS1
    io.out.aluSel2     := ALUOp2Sel.OP2_IMM
    io.out.immType     := ImmType.IMM_I
    io.out.memOp       := op
    io.out.memEn       := true.B
    io.out.wbSel       := WBSel.WB_MEM
    io.out.rfWen       := true.B
    invalidInst := false.B
  }
  when(inst === LB) { lInst(MemUOpType.mem_LB) }
  when(inst === LH) { lInst(MemUOpType.mem_LH) }
  when(inst === LW) { lInst(MemUOpType.mem_LW) }
  when(inst === LBU) { lInst(MemUOpType.mem_LBU) }
  when(inst === LHU) { lInst(MemUOpType.mem_LHU) }

  /* ---------- Store: sw rs2, offset(rs1) ---------- */
  private def sInst(op: MemUOpType.Type): Unit /*无返回值*/ = {
    io.out.aluOp       := ALUOpType.alu_ADD
    io.out.aluSel1     := ALUOp1Sel.OP1_RS1
    io.out.aluSel2     := ALUOp2Sel.OP2_IMM
    io.out.immType     := ImmType.IMM_S
    io.out.memOp       := op
    io.out.memEn       := true.B
    invalidInst := false.B
  }
  when(inst === SB) { sInst(MemUOpType.mem_SB) }
  when(inst === SH) { sInst(MemUOpType.mem_SH) }
  when(inst === SW) { sInst(MemUOpType.mem_SW) }

  /* ---------- Branch: beq rs1, rs2, offset ---------- */
  private def bInst(op: BRUOpType.Type): Unit /*无返回值*/ = {
    io.out.aluOp       := ALUOpType.alu_ADD
    io.out.aluSel1     := ALUOp1Sel.OP1_PC
    io.out.aluSel2     := ALUOp2Sel.OP2_IMM
    io.out.bruOp       := op
    io.out.immType     := ImmType.IMM_B
    io.out.npcOp       := NPCOpType.NPC_BR
    invalidInst        := false.B
  }
  when(inst === BEQ) { bInst(BRUOpType.bru_BEQ) }
  when(inst === BNE) { bInst(BRUOpType.bru_BNE) }
  when(inst === BLT) { bInst(BRUOpType.bru_BLT) }
  when(inst === BGE) { bInst(BRUOpType.bru_BGE) }
  when(inst === BLTU) { bInst(BRUOpType.bru_BLTU) }
  when(inst === BGEU) { bInst(BRUOpType.bru_BGEU) }

  /* ---------- JAL: jal rd, offset ---------- */
  when(inst === JAL) {
    io.out.aluOp       := ALUOpType.alu_ADD
    io.out.aluSel1     := ALUOp1Sel.OP1_PC
    io.out.aluSel2     := ALUOp2Sel.OP2_IMM
    io.out.immType     := ImmType.IMM_J
    io.out.npcOp       := NPCOpType.NPC_JAL
    io.out.wbSel       := WBSel.WB_PC4
    io.out.rfWen       := true.B
    invalidInst := false.B
  }

  /* ---------- JALR: jalr rd, rs1, offset ---------- */
  when(inst === JALR) {
    io.out.aluOp       := ALUOpType.alu_ADD // ALU 计算 rs1 + imm
    io.out.aluSel1     := ALUOp1Sel.OP1_RS1
    io.out.aluSel2     := ALUOp2Sel.OP2_IMM
    io.out.immType     := ImmType.IMM_I
    io.out.npcOp       := NPCOpType.NPC_JALR
    io.out.wbSel       := WBSel.WB_PC4
    io.out.rfWen       := true.B
    invalidInst := false.B
  }

  /* ---------- LUI: lui rd, imm ---------- */
  when(inst === LUI) {
    io.out.aluOp       := ALUOpType.alu_ADD
    io.out.aluSel1     := ALUOp1Sel.OP1_ZERO
    io.out.aluSel2     := ALUOp2Sel.OP2_IMM
    io.out.immType     := ImmType.IMM_U
    io.out.wbSel       := WBSel.WB_ALU
    io.out.rfWen       := true.B
    invalidInst := false.B
  }

  /* ---------- AUIPC: auipc rd, imm ---------- */
  when(inst === AUIPC) {
    io.out.aluOp       := ALUOpType.alu_ADD
    io.out.aluSel1     := ALUOp1Sel.OP1_PC
    io.out.aluSel2     := ALUOp2Sel.OP2_IMM
    io.out.immType     := ImmType.IMM_U
    io.out.wbSel       := WBSel.WB_ALU
    io.out.rfWen       := true.B
    invalidInst := false.B
  }

  /* ---------- EBREAK: ebreak ---------- */
  when(inst === EBREAK) {
    isEbreak    := true.B
    invalidInst := false.B
  }

  /* ---------- CSR 指令 ---------- */
  // CSRRW: t = CSRs[csr]; CSRs[csr] = rs1; rd = t
  when(inst === CSRRW) {
    io.out.csrOp       := CSROpType.CSR_RW
    io.out.csrWen      := true.B
    io.out.wbSel       := WBSel.WB_CSR
    io.out.rfWen       := true.B
    invalidInst := false.B
  }

  /* ---------- MRET: 从异常返回 ---------- */
  when(inst === MRET) {
    io.out.npcOp := NPCOpType.NPC_MRET
    invalidInst := false.B
  }

  // CSRRS: t = CSRs[csr]; CSRs[csr] = t | rs1; rd = t
  when(inst === CSRRS) {
    io.out.csrOp       := CSROpType.CSR_RS
    io.out.csrWen      := true.B
    io.out.wbSel       := WBSel.WB_CSR
    io.out.rfWen       := true.B
    invalidInst := false.B
  }

  /* ---------- ECALL: 触发环境调用异常 ---------- */
  when(inst === ECALL) {
    isEcall     := true.B
    invalidInst := false.B
  }
}
