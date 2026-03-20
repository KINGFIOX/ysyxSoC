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
  val CSR_RW, CSR_RS = Value // NOP, Read-Write, Read-Set
}

object InstType extends ChiselEnum {
  val INVALID, R_ALU, I_ALU, JALR, LOAD, STORE, BRANCH, JAL, LUI, AUIPC, ECALL,
      EBREAK, MRET, CSR = Value
}

class CUOutputBase extends NPCBundle {
  val inst_type = InstType()
  val alu_op = ALUOpType() // ALU 控制
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
  val addr = UInt(addrBits.W)
  val wdata = UInt(dataBits.W)
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
  private val OP_R      = "0110011" // R-type                      -> ALU
  private val OP_I_ALU  = "0010011" // I-type                      -> ALU
  private val OP_JALR   = "1100111" // JALR                        -> ALU
  private val OP_LOAD   = "0000011" // Load                        -> AGU
  private val OP_STORE  = "0100011" // Store                       -> AGU
  private val OP_BRANCH = "1100011" // Branch                      -> BRU
  private val OP_JAL    = "1101111" // JAL                         -> dispatch_resolved
  private val OP_LUI    = "0110111" // LUI                         -> dispatch_resolved
  private val OP_AUIPC  = "0010111" // AUIPC                       -> dispatch_resolved
  private val OP_SYSTEM = "1110011" // ECALL / EBREAK / MRET / CSR -> 
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
    InstPattern( func7 = BitPat("b000000?"), func3 = BitPat("b001"), opcode = BitPat("b0010011")), // SLLI
    InstPattern( func7 = BitPat("b000000?"), func3 = BitPat("b101"), opcode = BitPat("b0010011")), // SRLI
    InstPattern( func7 = BitPat("b010000?"), func3 = BitPat("b101"), opcode = BitPat("b0010011")), // SRAI
    InstPattern(func3 = BitPat("b010"), opcode = BitPat("b0010011")), // SLTI
    InstPattern(func3 = BitPat("b011"), opcode = BitPat("b0010011")), // SLTIU
    // Load (func3 + opcode)
    InstPattern(func3 = BitPat("b000"), opcode = BitPat("b0000011")), // LB
    InstPattern(func3 = BitPat("b001"), opcode = BitPat("b0000011")), // LH
    InstPattern(func3 = BitPat("b010"), opcode = BitPat("b0000011")), // LW
    InstPattern(func3 = BitPat("b100"), opcode = BitPat("b0000011")), // LBU
    InstPattern(func3 = BitPat("b101"), opcode = BitPat("b0000011")), // LHU
    // Store (func3 + opcode)
    InstPattern(func3 = BitPat("b000"), opcode = BitPat("b0100011")), // SB
    InstPattern(func3 = BitPat("b001"), opcode = BitPat("b0100011")), // SH
    InstPattern(func3 = BitPat("b010"), opcode = BitPat("b0100011")), // SW
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
    // CSR (func3 + opcode)
    InstPattern(func3 = BitPat("b001"), opcode = BitPat("b1110011")), // CSRRW
    InstPattern(func3 = BitPat("b010"), opcode = BitPat("b1110011")) // CSRRS
  )
  // format: on

  // ==================== DecodeField 对象 ====================

  object InstTypeField extends DecodeField[InstPattern, UInt] {
    def name = "inst_type"
    def chiselType = UInt(log2Ceil(InstType.all.length).W)
    private def bp(v: InstType.Type): BitPat = litBP(v.litValue, width)
    def genTable(op: InstPattern): BitPat = op.opcode.rawString match {
      case OP_R      => bp(InstType.R_ALU)
      case OP_I_ALU  => bp(InstType.I_ALU)
      case OP_JALR   => bp(InstType.JALR)
      case OP_LOAD   => bp(InstType.LOAD)
      case OP_STORE  => bp(InstType.STORE)
      case OP_BRANCH => bp(InstType.BRANCH)
      case OP_JAL    => bp(InstType.JAL)
      case OP_LUI    => bp(InstType.LUI)
      case OP_AUIPC  => bp(InstType.AUIPC)
      case OP_SYSTEM =>
        (op.func7.rawString, op.rs2.rawString, op.func3.rawString) match {
          case ("0000000", "00000", "000") => bp(InstType.ECALL)
          case ("0000000", "00001", "000") => bp(InstType.EBREAK)
          case ("0011000", "00010", "000") => bp(InstType.MRET)
          case ("???????", "?????", "001") => bp(InstType.CSR)
          case ("???????", "?????", "010") => bp(InstType.CSR)
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
      case OP_JALR => bp(ALUOpType.alu_ADD)
      case OP_SYSTEM => op.func3.rawString match {
        case "001" | "010" => bp(ALUOpType.alu_ADD)
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
      case OP_I_ALU | OP_LOAD | OP_JALR | OP_SYSTEM => bp(ImmType.IMM_I)
      case OP_STORE                                 => bp(ImmType.IMM_S)
      case OP_BRANCH                                => bp(ImmType.IMM_B)
      case OP_LUI | OP_AUIPC                        => bp(ImmType.IMM_U)
      case OP_JAL                                   => bp(ImmType.IMM_J)
      case _                                        => dc
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
          case "100" => bp(0) // LBU
          case "101" => bp(1) // LHU
          case _     => dc
        }
      case OP_STORE =>
        op.func3.rawString match {
          case "000" => bp(0) // SB
          case "001" => bp(1) // SH
          case "010" => bp(2) // SW
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
          case "001" | "010" => y // CSRRW, CSRRS
          case _             => n
        }
      case _ => n
    }
  }
  // format: on

  // ==================== DecodeTable ====================

  val allFields: Seq[DecodeField[InstPattern, _ <: Data]] = Seq(
    InstTypeField,
    AluOpField,
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
  io.out.imm_type := ImmType.safe(decoded(ImmTypeField))._1
  io.out.bru_op := BRUOpType.safe(decoded(BruOpField))._1
  io.out.mem.r_en := inst_type === InstType.LOAD
  io.out.mem.w_en := inst_type === InstType.STORE
  io.out.mem.size := decoded(MemSizeField)
  io.out.mem.sign_ext := decoded(MemSignExtField)
  io.out.mem.addr := 0.U
  io.out.mem.wdata := 0.U

  io.out.csr_op := CSROpType.safe(decoded(CsrOpField))._1
  io.out.csr_wen := decoded(CsrWenField)

  // mcause:
  // 2. illegal instruction
  // 3. breakpoint
  // 11. ecall from M-mode
  io.out.mcause := MuxLookup(inst_type, 0.U)(
    Seq(
      InstType.ECALL -> 11.U,
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
