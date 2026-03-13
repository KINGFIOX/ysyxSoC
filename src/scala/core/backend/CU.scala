package ysyx.core.backend

import chisel3._
import chisel3.util._
import chisel3.util.experimental.decode._

import ysyx.core.common._

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
  val cu_ILLEGAL_INSTRUCTION, cu_BREAKPOINT,
      cu_ECALL_FROM_U_MODE, // TODO: 暂时只有 M-mode 的 ecall
  cu_ECALL_FROM_S_MODE, cu_ECALL_FROM_M_MODE = Value
}

/** CU 输出的控制信号 */
class CUOutput extends NPCBundle {
  val aluOp = ALUOpType() // ALU 控制
  val aluSel1 = ALUOp1Sel()
  val aluSel2 = ALUOp2Sel()
  val immType = ImmType() // igu
  val npcOp = NPCOpType() // next_pc
  val bruOp = BRUOpType() // bru
  val mem = new MemInfoBundle // mem
  val wbSel = WBSel() // write back
  val rfWen = Bool()
  val csrOp = CSROpType() // csr
  val csrWen = Bool()
  val exception = CUExceptionType() // exception
  val exceptionEn = Bool()
  val mtval = UInt(dataBits.W) // access fault address
}

class MemInfoBundle extends NPCBundle {
  val size = UInt(2.W) // 0, 1, 2
  val r_en = Bool()
  val sign_ext = Bool() // need sign extension ? only used when load
  val w_en = Bool()
  val addr = UInt(addrBits.W)
  val wdata = UInt(dataBits.W)
}

class CUInput extends NPCBundle {
  val inst = UInt(InstBits.W)
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
  private val OP_R      = "0110011" // R-type
  private val OP_I_ALU  = "0010011" // I-type ALU
  private val OP_LOAD   = "0000011" // Load
  private val OP_STORE  = "0100011" // Store
  private val OP_BRANCH = "1100011" // Branch
  private val OP_JAL    = "1101111" // JAL
  private val OP_JALR   = "1100111" // JALR
  private val OP_LUI    = "0110111" // LUI
  private val OP_AUIPC  = "0010111" // AUIPC
  private val OP_SYSTEM = "1110011" // ECALL / EBREAK / MRET / CSR
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

  // format: off
  object AluOpField extends DecodeField[InstPattern, UInt] {
    def name = "aluOp"
    def chiselType = UInt(log2Ceil(ALUOpType.all.length).W)
    private def bp(v: ALUOpType.Type): BitPat = litBP(v.litValue, width)
    def genTable(op: InstPattern): BitPat = op.opcode.rawString match {
      case OP_R =>
        (op.func7.rawString, op.func3.rawString) match {
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
      case OP_I_ALU =>
        (op.func7.rawString, op.func3.rawString) match {
          case (_, "000")         => bp(ALUOpType.alu_ADD) // ADDI
          case (_, "111")         => bp(ALUOpType.alu_AND) // ANDI
          case (_, "110")         => bp(ALUOpType.alu_OR) // ORI
          case (_, "100")         => bp(ALUOpType.alu_XOR) // XORI
          case (_, "010")         => bp(ALUOpType.alu_SLT) // SLTI
          case (_, "011")         => bp(ALUOpType.alu_SLTU) // SLTIU
          case ("000000?", "001") => bp(ALUOpType.alu_SLL) // SLLI
          case ("000000?", "101") => bp(ALUOpType.alu_SRL) // SRLI
          case ("010000?", "101") => bp(ALUOpType.alu_SRA) // SRAI
          case _                  => dc
        }
      case OP_LOAD | OP_STORE | OP_BRANCH | OP_JAL | OP_JALR | OP_LUI | OP_AUIPC => bp(ALUOpType.alu_ADD)
      case _ => dc
    }
  }
  // format: on

  // format: off
  object AluSel1Field extends DecodeField[InstPattern, UInt] {
    def name = "aluSel1"
    def chiselType = UInt(log2Ceil(ALUOp1Sel.all.length).W)
    private def bp(v: ALUOp1Sel.Type): BitPat = litBP(v.litValue, width)
    def genTable(op: InstPattern): BitPat = op.opcode.rawString match {
      case OP_R | OP_I_ALU | OP_LOAD | OP_STORE | OP_JALR => bp(ALUOp1Sel.OP1_RS1)
      case OP_BRANCH | OP_JAL | OP_AUIPC                  => bp(ALUOp1Sel.OP1_PC)
      case OP_LUI                                         => bp(ALUOp1Sel.OP1_ZERO)
      case _                                              => dc
    }
  }
  // format: on

  // format: off
  object AluSel2Field extends DecodeField[InstPattern, UInt] {
    def name = "aluSel2"
    def chiselType = UInt(log2Ceil(ALUOp2Sel.all.length).W)
    private def bp(v: ALUOp2Sel.Type): BitPat = litBP(v.litValue, width)
    def genTable(op: InstPattern): BitPat = op.opcode.rawString match {
      case OP_R => bp(ALUOp2Sel.OP2_RS2)
      case OP_I_ALU | OP_LOAD | OP_STORE | OP_BRANCH | OP_JAL | OP_JALR | OP_LUI | OP_AUIPC => bp(ALUOp2Sel.OP2_IMM)
      case _ => dc
    }
  }
  // format: on

  // format: off
  object ImmTypeField extends DecodeField[InstPattern, UInt] {
    def name = "immType"
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
  object NpcOpField extends DecodeField[InstPattern, UInt] {
    def name = "npcOp"
    def chiselType = UInt(log2Ceil(NPCOpType.all.length).W)
    override def default: BitPat = litBP(NPCOpType.NPC_4.litValue, width)
    private def bp(v: NPCOpType.Type): BitPat = litBP(v.litValue, width)
    def genTable(op: InstPattern): BitPat = op.opcode.rawString match {
      case OP_BRANCH => bp(NPCOpType.NPC_BR)
      case OP_JAL    => bp(NPCOpType.NPC_JAL)
      case OP_JALR   => bp(NPCOpType.NPC_JALR)
      case OP_SYSTEM =>
        (op.func7.rawString, op.rs2.rawString) match {
          case ("0011000", "00010") => bp(NPCOpType.NPC_MRET)
          case _                    => bp(NPCOpType.NPC_4)
        }
      case _ => bp(NPCOpType.NPC_4)
    }
  }
  // format: on

  // format: off
  object BruOpField extends DecodeField[InstPattern, UInt] {
    def name = "bruOp"
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
    def name = "memSize"
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

  // load
  // format: off
  object MemRenField extends BoolDecodeField[InstPattern] {
    def name = "memRen"
    def genTable(op: InstPattern): BitPat = op.opcode.rawString match {
      case OP_LOAD => y
      case _       => n
    }
  }
  // format: on

  // store
  // format: off
  object MemWenField extends BoolDecodeField[InstPattern] {
    def name = "memWen"
    def genTable(op: InstPattern): BitPat = op.opcode.rawString match {
      case OP_STORE => y
      case _        => n
    }
  }
  // format: on

  // format: off
  object MemSignExtField extends BoolDecodeField[InstPattern] {
    def name = "memSignExt"
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
  object WbSelField extends DecodeField[InstPattern, UInt] {
    def name = "wbSel"
    def chiselType = UInt(log2Ceil(WBSel.all.length).W)
    private def bp(v: WBSel.Type): BitPat = litBP(v.litValue, width)
    def genTable(op: InstPattern): BitPat = op.opcode.rawString match {
      case OP_R | OP_I_ALU | OP_LUI | OP_AUIPC => bp(WBSel.WB_ALU)
      case OP_LOAD                             => bp(WBSel.WB_MEM)
      case OP_JAL | OP_JALR                    => bp(WBSel.WB_PC4)
      case OP_SYSTEM                           =>
        op.func3.rawString match {
          case "001" | "010" => bp(WBSel.WB_CSR) // CSRRW, CSRRS
          case _             => dc
        }
      case _ => dc
    }
  }
  // format: on

  // format: off
  object RfWenField extends BoolDecodeField[InstPattern] {
    def name = "rfWen"
    def genTable(op: InstPattern): BitPat = op.opcode.rawString match {
      case OP_R | OP_I_ALU | OP_LOAD | OP_JAL | OP_JALR | OP_LUI | OP_AUIPC => y
      case OP_SYSTEM                                                        =>
        op.func3.rawString match {
          case "001" | "010" => y // CSRRW, CSRRS
          case _             => n
        }
      case _ => n
    }
  }
  // format: on

  // format: off
  object CsrOpField extends DecodeField[InstPattern, UInt] {
    def name = "csrOp"
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
    def name = "csrWen"
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

  // format: off
  object EbreakField extends BoolDecodeField[InstPattern] {
    def name = "ebreak"
    def genTable(op: InstPattern): BitPat = op.opcode.rawString match {
      case OP_SYSTEM =>
        (op.func7.rawString, op.rs2.rawString, op.func3.rawString) match {
          case ("0000000", "00001", "000") => y
          case _                           => n
        }
      case _ => n
    }
  }
  // format: on

  // format: off
  object EcallField extends BoolDecodeField[InstPattern] {
    def name = "ecall"
    def genTable(op: InstPattern): BitPat = op.opcode.rawString match {
      case OP_SYSTEM =>
        (op.func7.rawString, op.rs2.rawString, op.func3.rawString) match {
          case ("0000000", "00000", "000") => y
          case _                           => n
        }
      case _ => n
    }
  }
  // format: on

  // format: off
  object ValidField extends BoolDecodeField[InstPattern] {
    def name = "valid"
    override def default: BitPat = n
    def genTable(op: InstPattern): BitPat = y
  }
  // format: on

  // ==================== DecodeTable ====================

  val allFields: Seq[DecodeField[InstPattern, _ <: Data]] = Seq(
    AluOpField,
    AluSel1Field,
    AluSel2Field,
    ImmTypeField,
    NpcOpField,
    BruOpField,
    MemRenField,
    MemWenField,
    MemSizeField,
    MemSignExtField,
    WbSelField,
    RfWenField,
    CsrOpField,
    CsrWenField,
    EbreakField,
    EcallField,
    ValidField
  )

  val decodeTable = new DecodeTable[InstPattern](allInstructions, allFields)
}

class CU extends NPCModule {
  val io = IO(new Bundle {
    val in = Flipped(new CUInput)
    val out = new CUOutput
  })

  import CU._

  private val inst = io.in.inst
  private val decoded = decodeTable.decode(inst)

  io.out.aluOp := ALUOpType.safe(decoded(AluOpField))._1
  io.out.aluSel1 := ALUOp1Sel.safe(decoded(AluSel1Field))._1
  io.out.aluSel2 := ALUOp2Sel.safe(decoded(AluSel2Field))._1
  io.out.immType := ImmType.safe(decoded(ImmTypeField))._1
  io.out.npcOp := NPCOpType.safe(decoded(NpcOpField))._1
  io.out.bruOp := BRUOpType.safe(decoded(BruOpField))._1
  io.out.mem.r_en := decoded(MemRenField)
  io.out.mem.w_en := decoded(MemWenField)
  io.out.mem.size := decoded(MemSizeField)
  io.out.mem.sign_ext := decoded(MemSignExtField)
  io.out.mem.addr := 0.U
  io.out.mem.wdata := 0.U
  io.out.wbSel := WBSel.safe(decoded(WbSelField))._1
  io.out.rfWen := decoded(RfWenField)
  io.out.csrOp := CSROpType.safe(decoded(CsrOpField))._1
  io.out.csrWen := decoded(CsrWenField)

  private val isEbreak = decoded(EbreakField)
  private val isEcall = decoded(EcallField)
  private val invalidInst = !decoded(ValidField)

  io.out.exception := MuxCase(
    DontCare,
    Seq(
      isEbreak -> CUExceptionType.cu_BREAKPOINT,
      isEcall -> CUExceptionType.cu_ECALL_FROM_M_MODE,
      invalidInst -> CUExceptionType.cu_ILLEGAL_INSTRUCTION
    )
  )
  io.out.exceptionEn := isEbreak || isEcall || invalidInst
  io.out.mtval := 0.U
}
