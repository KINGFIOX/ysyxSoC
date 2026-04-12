package ysyx.core.backend

import chisel3._
import chisel3.util._
import chisel3.util.experimental.decode._

import ysyx.core.common._

/** ALU 操作数2 选择 */
object ALUSel2 extends ChiselEnum {
  val OP2_RS2, OP2_IMM = Value
}

/** CSR 操作类型 */
object CSROpType extends ChiselEnum {
  val CSR_RW, CSR_RS, CSR_RC = Value
}

object InstType extends ChiselEnum {
  val INVALID, R_ALU, I_ALU, JALR, LOAD, STORE, BRANCH, JAL,
      LUI, AUIPC, ECALL, EBREAK, MRET, SRET, SFENCE_VMA, CSR,
      FENCE, FENCE_I, R_MUL = Value
}

object MDUOpType extends ChiselEnum {
  val mdu_X, mdu_MUL, mdu_MULH, mdu_MULHSU, mdu_MULHU,
      mdu_DIV, mdu_DIVU, mdu_REM, mdu_REMU,
      mdu_MULW, mdu_DIVW, mdu_DIVUW, mdu_REMW, mdu_REMUW = Value
}

class CUOutputBase extends NPCBundle {
  val inst_type = InstType()
  val alu_op = ALUOpType() // ALU 控制
  val mdu_op = MDUOpType() // MDU 控制
  val imm_type = ImmType()
  val bru_op = BRUOpType() // bru
  val mem = new MemInfoBundle // mem
  val csr_op = CSROpType() // csr
  val csr_wen = Bool()
}

class CUOutput extends CUOutputBase {
  val has_except = Bool()
  val mcause = UInt(dataBits.W)
  val mtval = UInt(dataBits.W)
}

class MemInfoBundle extends NPCBundle {
  val size = UInt(axiParams.sizeBits.W) // 0, 1, 2
  val r_en = Bool()
  val sign_ext = Bool() // need sign extension ? only used when load
  val w_en = Bool()
}

class CUInput extends NPCBundle {
  val inst = UInt(instBits.W)
  val pc = UInt(addrBits.W)
}

object CU {
  // format: off
  private def litBP(value: BigInt, w: Int): BitPat = BitPat("b" + value.toString(2).reverse.padTo(w, '0').reverse)
  // format: on

  // ==================== DecodePattern ====================

  // format: off
  case class InstPattern(
      func7: BitPat = BitPat.dontCare(7),
      rs2: BitPat = BitPat.dontCare(5), // system
      func3: BitPat = BitPat.dontCare(3),
      opcode: BitPat
  ) extends DecodePattern {
    def bitPat: BitPat = func7 ## rs2 ## BitPat.dontCare(5) ## func3 ## BitPat.dontCare( 5) ## opcode
  }
  // format: on

  // format: off
  private val OP_R       = "0110011" // R-type                      -> ALU
  private val OP_I_ALU   = "0010011" // I-type                      -> ALU
  private val OP_R_W     = "0111011" // RV64 R-type *W              -> ALU (32-bit op)
  private val OP_I_ALU_W = "0011011" // RV64 I-type *W              -> ALU (32-bit op)
  private val OP_JALR    = "1100111" // JALR                        -> ALU
  private val OP_LOAD    = "0000011" // Load                        -> AGU
  private val OP_STORE   = "0100011" // Store                       -> AGU
  private val OP_BRANCH  = "1100011" // Branch                      -> BRU
  private val OP_JAL     = "1101111" // JAL                         -> dispatch_resolved
  private val OP_LUI     = "0110111" // LUI                         -> dispatch_resolved
  private val OP_AUIPC   = "0010111" // AUIPC                       -> dispatch_resolved
  private val OP_SYSTEM  = "1110011" // ECALL / EBREAK / MRET / CSR -> 
  private val OP_MISC_MEM = "0001111" // FENCE / FENCE.I
  // format: on

  // ==================== 指令表 (纯编码信息) ====================

  // format: off
  val allInstructions: Seq[InstPattern] = Seq(
    // R-type (func7 + func3 + opcode)
    InstPattern( func7 = BitPat("b0000000"), func3 = BitPat("b000"), opcode = BitPat("b0110011")), // ADD
    InstPattern( func7 = BitPat("b0100000"), func3 = BitPat("b000"), opcode = BitPat("b0110011")), // SUB
    InstPattern( func7 = BitPat("b0000000"), func3 = BitPat("b111"), opcode = BitPat("b0110011")), // AND
    InstPattern( func7 = BitPat("b0000000"), func3 = BitPat("b110"), opcode = BitPat("b0110011")), // OR
    InstPattern( func7 = BitPat("b0000000"), func3 = BitPat("b100"), opcode = BitPat("b0110011")), // XOR
    InstPattern( func7 = BitPat("b0000000"), func3 = BitPat("b001"), opcode = BitPat("b0110011")), // SLL
    InstPattern( func7 = BitPat("b0000000"), func3 = BitPat("b101"), opcode = BitPat("b0110011")), // SRL
    InstPattern( func7 = BitPat("b0100000"), func3 = BitPat("b101"), opcode = BitPat("b0110011")), // SRA
    InstPattern( func7 = BitPat("b0000000"), func3 = BitPat("b010"), opcode = BitPat("b0110011")), // SLT
    InstPattern( func7 = BitPat("b0000000"), func3 = BitPat("b011"), opcode = BitPat("b0110011")), // SLTU
    // I-type ALU (func3 + opcode, shift 需要 func7)
    InstPattern(func3 = BitPat("b000"), opcode = BitPat("b0010011")), // ADDI
    InstPattern(func3 = BitPat("b111"), opcode = BitPat("b0010011")), // ANDI
    InstPattern(func3 = BitPat("b110"), opcode = BitPat("b0010011")), // ORI
    InstPattern(func3 = BitPat("b100"), opcode = BitPat("b0010011")), // XORI
    InstPattern( func7 = BitPat("b000000?"), func3 = BitPat("b001"), opcode = BitPat("b0010011")), // SLLI (RV64: shamt[5:0])
    InstPattern( func7 = BitPat("b000000?"), func3 = BitPat("b101"), opcode = BitPat("b0010011")), // SRLI (RV64: shamt[5:0])
    InstPattern( func7 = BitPat("b010000?"), func3 = BitPat("b101"), opcode = BitPat("b0010011")), // SRAI (RV64: shamt[5:0])
    InstPattern(func3 = BitPat("b010"), opcode = BitPat("b0010011")), // SLTI
    InstPattern(func3 = BitPat("b011"), opcode = BitPat("b0010011")), // SLTIU
    // RV64 I-type ALU *W (func3 + opcode, ADDIW; shift needs func7)
    InstPattern(func3 = BitPat("b000"), opcode = BitPat("b0011011")), // ADDIW
    InstPattern( func7 = BitPat("b0000000"), func3 = BitPat("b001"), opcode = BitPat("b0011011")), // SLLIW
    InstPattern( func7 = BitPat("b0000000"), func3 = BitPat("b101"), opcode = BitPat("b0011011")), // SRLIW
    InstPattern( func7 = BitPat("b0100000"), func3 = BitPat("b101"), opcode = BitPat("b0011011")), // SRAIW
    // RV64 R-type *W (func7 + func3 + opcode)
    InstPattern( func7 = BitPat("b0000000"), func3 = BitPat("b000"), opcode = BitPat("b0111011")), // ADDW
    InstPattern( func7 = BitPat("b0100000"), func3 = BitPat("b000"), opcode = BitPat("b0111011")), // SUBW
    InstPattern( func7 = BitPat("b0000000"), func3 = BitPat("b001"), opcode = BitPat("b0111011")), // SLLW
    InstPattern( func7 = BitPat("b0000000"), func3 = BitPat("b101"), opcode = BitPat("b0111011")), // SRLW
    InstPattern( func7 = BitPat("b0100000"), func3 = BitPat("b101"), opcode = BitPat("b0111011")), // SRAW
    // RV64M (func7=0000001 + func3 + opcode=0110011)
    InstPattern( func7 = BitPat("b0000001"), func3 = BitPat("b000"), opcode = BitPat("b0110011")), // MUL
    InstPattern( func7 = BitPat("b0000001"), func3 = BitPat("b001"), opcode = BitPat("b0110011")), // MULH
    InstPattern( func7 = BitPat("b0000001"), func3 = BitPat("b010"), opcode = BitPat("b0110011")), // MULHSU
    InstPattern( func7 = BitPat("b0000001"), func3 = BitPat("b011"), opcode = BitPat("b0110011")), // MULHU
    InstPattern( func7 = BitPat("b0000001"), func3 = BitPat("b100"), opcode = BitPat("b0110011")), // DIV
    InstPattern( func7 = BitPat("b0000001"), func3 = BitPat("b101"), opcode = BitPat("b0110011")), // DIVU
    InstPattern( func7 = BitPat("b0000001"), func3 = BitPat("b110"), opcode = BitPat("b0110011")), // REM
    InstPattern( func7 = BitPat("b0000001"), func3 = BitPat("b111"), opcode = BitPat("b0110011")), // REMU
    // RV64M *W (func7=0000001 + func3 + opcode=0111011)
    InstPattern( func7 = BitPat("b0000001"), func3 = BitPat("b000"), opcode = BitPat("b0111011")), // MULW
    InstPattern( func7 = BitPat("b0000001"), func3 = BitPat("b100"), opcode = BitPat("b0111011")), // DIVW
    InstPattern( func7 = BitPat("b0000001"), func3 = BitPat("b101"), opcode = BitPat("b0111011")), // DIVUW
    InstPattern( func7 = BitPat("b0000001"), func3 = BitPat("b110"), opcode = BitPat("b0111011")), // REMW
    InstPattern( func7 = BitPat("b0000001"), func3 = BitPat("b111"), opcode = BitPat("b0111011")), // REMUW
    // Load (func3 + opcode)
    InstPattern(func3 = BitPat("b000"), opcode = BitPat("b0000011")), // LB
    InstPattern(func3 = BitPat("b001"), opcode = BitPat("b0000011")), // LH
    InstPattern(func3 = BitPat("b010"), opcode = BitPat("b0000011")), // LW
    InstPattern(func3 = BitPat("b011"), opcode = BitPat("b0000011")), // LD
    InstPattern(func3 = BitPat("b100"), opcode = BitPat("b0000011")), // LBU
    InstPattern(func3 = BitPat("b101"), opcode = BitPat("b0000011")), // LHU
    InstPattern(func3 = BitPat("b110"), opcode = BitPat("b0000011")), // LWU
    // Store (func3 + opcode)
    InstPattern(func3 = BitPat("b000"), opcode = BitPat("b0100011")), // SB
    InstPattern(func3 = BitPat("b001"), opcode = BitPat("b0100011")), // SH
    InstPattern(func3 = BitPat("b010"), opcode = BitPat("b0100011")), // SW
    InstPattern(func3 = BitPat("b011"), opcode = BitPat("b0100011")), // SD
    // Branch (func3 + opcode)
    InstPattern(func3 = BitPat("b000"), opcode = BitPat("b1100011")), // BEQ
    InstPattern(func3 = BitPat("b001"), opcode = BitPat("b1100011")), // BNE
    InstPattern(func3 = BitPat("b100"), opcode = BitPat("b1100011")), // BLT
    InstPattern(func3 = BitPat("b101"), opcode = BitPat("b1100011")), // BGE
    InstPattern(func3 = BitPat("b110"), opcode = BitPat("b1100011")), // BLTU
    InstPattern(func3 = BitPat("b111"), opcode = BitPat("b1100011")), // BGEU
    // JAL (opcode only)
    InstPattern(opcode = BitPat("b1101111")), // JAL
    // JALR (func3 + opcode)
    InstPattern(func3 = BitPat("b000"), opcode = BitPat("b1100111")), // JALR
    // U-type (opcode only)
    InstPattern(opcode = BitPat("b0110111")), // LUI
    InstPattern(opcode = BitPat("b0010111")), // AUIPC
    // System (func7 + rs2 + func3 + opcode)
    InstPattern( func7 = BitPat("b0000000"), rs2 = BitPat("b00000"), func3 = BitPat("b000"), opcode = BitPat("b1110011")), // ECALL
    InstPattern( func7 = BitPat("b0000000"), rs2 = BitPat("b00001"), func3 = BitPat("b000"), opcode = BitPat("b1110011")), // EBREAK
    InstPattern( func7 = BitPat("b0011000"), rs2 = BitPat("b00010"), func3 = BitPat("b000"), opcode = BitPat("b1110011")), // MRET
    InstPattern( func7 = BitPat("b0001000"), rs2 = BitPat("b00010"), func3 = BitPat("b000"), opcode = BitPat("b1110011")), // SRET
    InstPattern( func7 = BitPat("b0001001"),                         func3 = BitPat("b000"), opcode = BitPat("b1110011")), // SFENCE.VMA
    // CSR (func3 + opcode)
    InstPattern(func3 = BitPat("b001"), opcode = BitPat("b1110011")), // CSRRW
    InstPattern(func3 = BitPat("b010"), opcode = BitPat("b1110011")), // CSRRS
    InstPattern(func3 = BitPat("b011"), opcode = BitPat("b1110011")), // CSRRC
    // FENCE / FENCE.I (func3 + opcode)
    InstPattern(func3 = BitPat("b000"), opcode = BitPat("b0001111")), // FENCE
    InstPattern(func3 = BitPat("b001"), opcode = BitPat("b0001111"))  // FENCE.I
  )
  // format: on

  // ==================== DecodeField 对象 ====================

  object InstTypeField extends DecodeField[InstPattern, UInt] {
    def name = "inst_type"
    def chiselType = UInt(log2Ceil(InstType.all.length).W)
    private def bp(v: InstType.Type): BitPat = litBP(v.litValue, width)
    def genTable(op: InstPattern): BitPat = op.opcode.rawString match {
      case OP_R => op.func7.rawString match {
          case "0000001" => bp(InstType.R_MUL)
          case _         => bp(InstType.R_ALU)
        }
      case OP_I_ALU   => bp(InstType.I_ALU)
      case OP_R_W => op.func7.rawString match {
          case "0000001" => bp(InstType.R_MUL)
          case _         => bp(InstType.R_ALU)
        }
      case OP_I_ALU_W => bp(InstType.I_ALU)
      case OP_JALR    => bp(InstType.JALR)
      case OP_LOAD    => bp(InstType.LOAD)
      case OP_STORE   => bp(InstType.STORE)
      case OP_BRANCH  => bp(InstType.BRANCH)
      case OP_JAL     => bp(InstType.JAL)
      case OP_LUI     => bp(InstType.LUI)
      case OP_AUIPC   => bp(InstType.AUIPC)
      case OP_SYSTEM =>
        (op.func7.rawString, op.rs2.rawString, op.func3.rawString) match {
          case ("0000000", "00000", "000") => bp(InstType.ECALL)
          case ("0000000", "00001", "000") => bp(InstType.EBREAK)
          case ("0011000", "00010", "000") => bp(InstType.MRET)
          case ("0001000", "00010", "000") => bp(InstType.SRET)
          case ("0001001", _,      "000") => bp(InstType.SFENCE_VMA)
          case ("???????", "?????", "001") => bp(InstType.CSR)
          case ("???????", "?????", "010") => bp(InstType.CSR)
          case ("???????", "?????", "011") => bp(InstType.CSR)
        }
      case OP_MISC_MEM => op.func3.rawString match {
          case "000" => bp(InstType.FENCE)
          case "001" => bp(InstType.FENCE_I)
        }
      case _ => bp(InstType.INVALID)
    }
  }

  // format: off
  object AluOpField extends DecodeField[InstPattern, UInt] {
    def name = "alu_op"
    def chiselType = UInt(log2Ceil(ALUOpType.all.length).W)
    private def bp(v: ALUOpType.Type): BitPat = litBP(v.litValue, width)
    def genTable(op: InstPattern): BitPat = op.opcode.rawString match {
      case OP_R => (op.func7.rawString, op.func3.rawString) match {
          case ("0000000", "000") => bp(ALUOpType.alu_ADD)
          case ("0100000", "000") => bp(ALUOpType.alu_SUB)
          case ("0000000", "111") => bp(ALUOpType.alu_AND)
          case ("0000000", "110") => bp(ALUOpType.alu_OR)
          case ("0000000", "100") => bp(ALUOpType.alu_XOR)
          case ("0000000", "001") => bp(ALUOpType.alu_SLL)
          case ("0000000", "101") => bp(ALUOpType.alu_SRL)
          case ("0100000", "101") => bp(ALUOpType.alu_SRA)
          case ("0000000", "010") => bp(ALUOpType.alu_SLT)
          case ("0000000", "011") => bp(ALUOpType.alu_SLTU)
          case _                  => dc
        }
      case OP_I_ALU => (op.func7.rawString, op.func3.rawString) match {
          case (_, "000")         => bp(ALUOpType.alu_ADD)  // ADDI
          case (_, "111")         => bp(ALUOpType.alu_AND)  // ANDI
          case (_, "110")         => bp(ALUOpType.alu_OR)   // ORI
          case (_, "100")         => bp(ALUOpType.alu_XOR)  // XORI
          case (_, "010")         => bp(ALUOpType.alu_SLT)  // SLTI
          case (_, "011")         => bp(ALUOpType.alu_SLTU) // SLTIU
          case ("000000?", "001") => bp(ALUOpType.alu_SLL)  // SLLI
          case ("000000?", "101") => bp(ALUOpType.alu_SRL)  // SRLI
          case ("010000?", "101") => bp(ALUOpType.alu_SRA)  // SRAI
          case _                  => dc
        }
      case OP_R_W => (op.func7.rawString, op.func3.rawString) match {
          case ("0000000", "000") => bp(ALUOpType.alu_ADDW)
          case ("0100000", "000") => bp(ALUOpType.alu_SUBW)
          case ("0000000", "001") => bp(ALUOpType.alu_SLLW)
          case ("0000000", "101") => bp(ALUOpType.alu_SRLW)
          case ("0100000", "101") => bp(ALUOpType.alu_SRAW)
          case _                  => dc
        }
      case OP_I_ALU_W => (op.func7.rawString, op.func3.rawString) match {
          case (_, "000")         => bp(ALUOpType.alu_ADDW)  // ADDIW
          case ("0000000", "001") => bp(ALUOpType.alu_SLLW)  // SLLIW
          case ("0000000", "101") => bp(ALUOpType.alu_SRLW)  // SRLIW
          case ("0100000", "101") => bp(ALUOpType.alu_SRAW)  // SRAIW
          case _                  => dc
        }
      case OP_JALR => bp(ALUOpType.alu_ADD)
      case OP_SYSTEM => op.func3.rawString match {
        case "001" | "010" | "011" => bp(ALUOpType.alu_ADD)
        case _ => dc
      }
      case _ => dc
    }
  }
  // format: on

  // format: off
  object ImmTypeField extends DecodeField[InstPattern, UInt] {
    def name = "imm_type"
    def chiselType = UInt(log2Ceil(ImmType.all.length).W)
    private def bp(v: ImmType.Type): BitPat = litBP(v.litValue, width)
    def genTable(op: InstPattern): BitPat = op.opcode.rawString match {
      case OP_I_ALU | OP_I_ALU_W | OP_LOAD | OP_JALR | OP_SYSTEM => bp(ImmType.IMM_I)
      case OP_STORE                                               => bp(ImmType.IMM_S)
      case OP_BRANCH                                              => bp(ImmType.IMM_B)
      case OP_LUI | OP_AUIPC                                     => bp(ImmType.IMM_U)
      case OP_JAL                                                 => bp(ImmType.IMM_J)
      case _                                                      => dc
    }
  }
  // format: on

  // format: off
  object BruOpField extends DecodeField[InstPattern, UInt] {
    def name = "bru_op"
    def chiselType = UInt(log2Ceil(BRUOpType.all.length).W)
    override def default: BitPat = litBP(BRUOpType.bru_X.litValue, width)
    private def bp(v: BRUOpType.Type): BitPat = litBP(v.litValue, width)
    def genTable(op: InstPattern): BitPat = op.opcode.rawString match {
      case OP_BRANCH =>
        op.func3.rawString match {
          case "000" => bp(BRUOpType.bru_BEQ)
          case "001" => bp(BRUOpType.bru_BNE)
          case "100" => bp(BRUOpType.bru_BLT)
          case "101" => bp(BRUOpType.bru_BGE)
          case "110" => bp(BRUOpType.bru_BLTU)
          case "111" => bp(BRUOpType.bru_BGEU)
          case _     => bp(BRUOpType.bru_X)
        }
      case _ => bp(BRUOpType.bru_X)
    }
  }
  // format: on

  // format: off
  object MemSizeField extends DecodeField[InstPattern, UInt] {
    def name = "mem_size"
    def chiselType = UInt(2.W)
    private def bp(v: Int): BitPat = litBP(v, width)
    def genTable(op: InstPattern): BitPat = op.opcode.rawString match {
      case OP_LOAD =>
        op.func3.rawString match {
          case "000" => bp(0) // LB
          case "001" => bp(1) // LH
          case "010" => bp(2) // LW
          case "011" => bp(3) // LD
          case "100" => bp(0) // LBU
          case "101" => bp(1) // LHU
          case "110" => bp(2) // LWU
          case _     => dc
        }
      case OP_STORE =>
        op.func3.rawString match {
          case "000" => bp(0) // SB
          case "001" => bp(1) // SH
          case "010" => bp(2) // SW
          case "011" => bp(3) // SD
          case _     => dc
        }
      case _ => dc
    }
  }
  // format: on

  // format: off
  object MemSignExtField extends BoolDecodeField[InstPattern] {
    def name = "mem_sign_ext"
    def genTable(op: InstPattern): BitPat = op.opcode.rawString match {
      case OP_LOAD =>
        op.func3.rawString match {
          case "100" => n // lbu — zero extend
          case "101" => n // lhu — zero extend
          case "110" => n // lwu — zero extend
          case _     => y
        }
      case _ => n
    }
  }
  // format: on

  // format: off
  object CsrOpField extends DecodeField[InstPattern, UInt] {
    def name = "csr_op"
    def chiselType = UInt(log2Ceil(CSROpType.all.length).W)
    private def bp(v: CSROpType.Type): BitPat = litBP(v.litValue, width)
    def genTable(op: InstPattern): BitPat = op.opcode.rawString match {
      case OP_SYSTEM =>
        op.func3.rawString match {
          case "001" => bp(CSROpType.CSR_RW)
          case "010" => bp(CSROpType.CSR_RS)
          case "011" => bp(CSROpType.CSR_RC)
          case _     => dc
        }
      case _ => dc
    }
  }
  // format: on

  // format: off
  object CsrWenField extends BoolDecodeField[InstPattern] {
    def name = "csr_wen"
    def genTable(op: InstPattern): BitPat = op.opcode.rawString match {
      case OP_SYSTEM =>
        op.func3.rawString match {
          case "001" | "010" | "011" => y // CSRRW, CSRRS, CSRRC
          case _                     => n
        }
      case _ => n
    }
  }
  // format: on

  // format: off
  object MduOpField extends DecodeField[InstPattern, UInt] {
    def name = "mdu_op"
    def chiselType = UInt(log2Ceil(MDUOpType.all.length).W)
    override def default: BitPat = litBP(MDUOpType.mdu_X.litValue, width)
    private def bp(v: MDUOpType.Type): BitPat = litBP(v.litValue, width)
    def genTable(op: InstPattern): BitPat = op.opcode.rawString match {
      case OP_R => (op.func7.rawString, op.func3.rawString) match {
          case ("0000001", "000") => bp(MDUOpType.mdu_MUL)
          case ("0000001", "001") => bp(MDUOpType.mdu_MULH)
          case ("0000001", "010") => bp(MDUOpType.mdu_MULHSU)
          case ("0000001", "011") => bp(MDUOpType.mdu_MULHU)
          case ("0000001", "100") => bp(MDUOpType.mdu_DIV)
          case ("0000001", "101") => bp(MDUOpType.mdu_DIVU)
          case ("0000001", "110") => bp(MDUOpType.mdu_REM)
          case ("0000001", "111") => bp(MDUOpType.mdu_REMU)
          case _                  => bp(MDUOpType.mdu_X)
        }
      case OP_R_W => (op.func7.rawString, op.func3.rawString) match {
          case ("0000001", "000") => bp(MDUOpType.mdu_MULW)
          case ("0000001", "100") => bp(MDUOpType.mdu_DIVW)
          case ("0000001", "101") => bp(MDUOpType.mdu_DIVUW)
          case ("0000001", "110") => bp(MDUOpType.mdu_REMW)
          case ("0000001", "111") => bp(MDUOpType.mdu_REMUW)
          case _                  => bp(MDUOpType.mdu_X)
        }
      case _ => bp(MDUOpType.mdu_X)
    }
  }
  // format: on

  // ==================== DecodeTable ====================

  val allFields: Seq[DecodeField[InstPattern, _ <: Data]] = Seq(
    InstTypeField,
    AluOpField,
    MduOpField,
    ImmTypeField,
    BruOpField,
    MemSizeField,
    MemSignExtField,
    CsrOpField,
    CsrWenField
  )

  val decodeTable = new DecodeTable[InstPattern](allInstructions, allFields)
}

class CU extends NPCModule {
  val io = IO(new Bundle {
    val in = Flipped(new CUInput)
    val out = new CUOutput
  })

  import CU._

  val inst = io.in.inst
  val decoded = decodeTable.decode(inst)

  val inst_type = InstType.safe(decoded(InstTypeField))._1

  io.out.inst_type := inst_type
  io.out.alu_op := ALUOpType.safe(decoded(AluOpField))._1
  io.out.mdu_op := MDUOpType.safe(decoded(MduOpField))._1
  io.out.imm_type := ImmType.safe(decoded(ImmTypeField))._1
  io.out.bru_op := BRUOpType.safe(decoded(BruOpField))._1
  io.out.mem.r_en := inst_type === InstType.LOAD
  io.out.mem.w_en := inst_type === InstType.STORE
  io.out.mem.size := decoded(MemSizeField)
  io.out.mem.sign_ext := decoded(MemSignExtField)

  io.out.csr_op := CSROpType.safe(decoded(CsrOpField))._1
  io.out.csr_wen := decoded(CsrWenField)

  // mcause: placeholder — ECALL cause is determined at commit time based on priv level
  // 0 = ECALL marker (will be rewritten by CommitStage as 8/9/11 depending on U/S/M)
  // 2 = illegal instruction, 3 = breakpoint
  io.out.mcause := MuxLookup(inst_type, 0.U)(
    Seq(
      InstType.ECALL -> 0.U,
      InstType.EBREAK -> 3.U,
      InstType.INVALID -> 2.U
    )
  )

  io.out.mtval := MuxLookup(inst_type, 0.U)(
    Seq(
      InstType.ECALL -> 0.U,
      InstType.EBREAK -> io.in.pc,
      InstType.INVALID -> io.in.inst
    )
  )
  io.out.has_except := Seq(InstType.EBREAK, InstType.ECALL, InstType.INVALID)
    .map(inst_type === _)
    .reduce(_ || _)
}
