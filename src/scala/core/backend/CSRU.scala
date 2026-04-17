package ysyx.core.backend

import chisel3._
import chisel3.util._

import ysyx.core.common._

class CsrTrapWritePort extends Bundle with HasCoreParameter {
  val xepc = UInt(dataBits.W)
  val xcause = UInt(dataBits.W)
  val xtval = UInt(dataBits.W)
  val is_interrupt = Bool()
}

class CsrWriteOnlyPort extends NPCBundle {
  val prs1 = UInt(NRPhyRegBits.W)
  val addr = UInt(NRCSRbits.W)
  val op = CSROpType()
  val wen = Bool()
  val result = Input(UInt(dataBits.W))
}

class CSRUDebugBundle
    extends Bundle
    with HasCoreParameter
    with HasCSRParameter {
  val mstatus = UInt(dataBits.W)
  val mtvec = UInt(dataBits.W)
  val mepc = UInt(dataBits.W)
  val mcause = UInt(dataBits.W)
  val mtval = UInt(dataBits.W)
  val mvendorid = UInt(dataBits.W)
  val marchid = UInt(dataBits.W)
}

class CSRU extends LateExecUnit(new CsrWriteOnlyPort, 1) {
  val probe = IO(new CSRUDebugBundle)
  val trap = IO(Flipped(Valid(new CsrTrapWritePort)))
  val mret_event = IO(Input(Bool()))
  val sret_event = IO(Input(Bool()))

  val mepc_out = IO(Output(UInt(dataBits.W)))
  val sepc_out = IO(Output(UInt(dataBits.W)))
  val trap_target = IO(Output(UInt(dataBits.W)))
  val priv_out = IO(Output(UInt(2.W)))

  val interrupt_pending = IO(Output(Bool()))
  val interrupt_cause = IO(Output(UInt(dataBits.W)))
  val interrupt_target = IO(Output(UInt(dataBits.W)))

  val ext_irq = IO(Input(Bool()))
  val mtime_in = IO(Input(UInt(64.W)))

  val sfence_vma = IO(Input(Bool()))
  val sfence_vma_out = IO(Output(Bool()))

  val satp_out = IO(Output(UInt(dataBits.W)))

  prf(0).addr := late.bits.prs1
  val wdata = prf(0).data

  late.done := late.req
  late.bits.result := 0.U

  // ================================================================
  // Privilege level
  // ================================================================
  val priv = RegInit(PRV_M.U(2.W))
  priv_out := priv

  // ================================================================
  // M-mode registers
  // ================================================================
  // Match Spike's compute_mstatus_initial_value(): SXL=UXL=2 (RV64), MPP=0.
  // priv is separately reset to M below, so boot still starts in M-mode.
  val mstatus    = RegInit("h0000000a00000000".U(dataBits.W))
  val mtvec      = RegInit(0.U(dataBits.W))
  val mepc       = RegInit(0.U(dataBits.W))
  val mcause     = RegInit(0.U(dataBits.W))
  val mtval      = RegInit(0.U(dataBits.W))
  val medeleg    = RegInit(0.U(dataBits.W))
  val mideleg    = RegInit(0.U(dataBits.W))
  val mie        = RegInit(0.U(dataBits.W))
  val mscratch   = RegInit(0.U(dataBits.W))
  val menvcfg    = RegInit(0.U(dataBits.W))
  val mcounteren = RegInit(0.U(dataBits.W))
  val pmpcfg0    = RegInit(0.U(dataBits.W))
  val pmpaddr0   = RegInit(0.U(dataBits.W))

  // S-mode registers
  val stvec    = RegInit(0.U(dataBits.W))
  val sepc     = RegInit(0.U(dataBits.W))
  val scause   = RegInit(0.U(dataBits.W))
  val stval    = RegInit(0.U(dataBits.W))
  val sscratch = RegInit(0.U(dataBits.W))
  val satp     = RegInit(0.U(dataBits.W))
  val stimecmp = RegInit(~0.U(dataBits.W))

  // Read-only registers
  val mvendorid = 0x7973_7978.U(dataBits.W)
  val marchid   = 26010003.U(dataBits.W)
  val mhartid   = 0.U(dataBits.W)

  // ================================================================
  // Derived signals: mip (interrupt pending bits driven by hardware)
  // ================================================================
  val mip_stip = mtime_in >= stimecmp
  val mip_seip = ext_irq
  val mip_reg_ssip = RegInit(false.B) // software-writable SSIP bit
  val mip = Cat(
    0.U(52.W),
    mip_seip,       // bit 9: SEIP
    0.U(1.W),       // bit 8
    0.U(1.W),       // bit 7: MTIP (unused for now)
    0.U(1.W),       // bit 6
    mip_stip,       // bit 5: STIP
    0.U(1.W),       // bit 4
    0.U(1.W),       // bit 3: MSIP (unused)
    0.U(1.W),       // bit 2
    mip_reg_ssip,   // bit 1: SSIP
    0.U(1.W)        // bit 0
  )

  // ================================================================
  // sstatus / sie / sip views (masked views of mstatus / mie / mip)
  // ================================================================
  val sstatus_mask = SSTATUS_MASK.U(dataBits.W)
  val sstatus_wmask = SSTATUS_WMASK.U(dataBits.W)
  val sie_mask = SIE_MASK.U(dataBits.W)
  val mstatus_sxl_uxl_mask = MSTATUS_SXL_UXL.U(dataBits.W)

  val sstatus_read = mstatus & sstatus_mask
  val sie_read = mie & sie_mask
  val sip_read = mip & sie_mask

  // ================================================================
  // Interrupt pending logic
  // ================================================================
  val s_interrupts = mip & mie & mideleg
  val m_interrupts = mip & mie & ~mideleg
  val s_enabled = (priv < PRV_S.U) || (priv === PRV_S.U && mstatus(MSTATUS_SIE_BIT))
  val m_enabled = (priv < PRV_M.U) || (priv === PRV_M.U && mstatus(MSTATUS_MIE_BIT))

  val s_pending = s_interrupts.orR && s_enabled
  val m_pending = m_interrupts.orR && m_enabled

  interrupt_pending := s_pending || m_pending

  // Priority: M-mode interrupts > S-mode interrupts
  // Within each: MEI > MSI > MTI > SEI > SSI > STI
  val m_cause = MuxCase(0.U, Seq(
    m_interrupts(IRQ_MEIP) -> IRQ_MEIP.U,
    m_interrupts(IRQ_MSIP) -> IRQ_MSIP.U,
    m_interrupts(IRQ_MTIP) -> IRQ_MTIP.U,
    m_interrupts(IRQ_SEIP) -> IRQ_SEIP.U,
    m_interrupts(IRQ_SSIP) -> IRQ_SSIP.U,
    m_interrupts(IRQ_STIP) -> IRQ_STIP.U
  ))
  val s_cause = MuxCase(0.U, Seq(
    s_interrupts(IRQ_SEIP) -> IRQ_SEIP.U,
    s_interrupts(IRQ_SSIP) -> IRQ_SSIP.U,
    s_interrupts(IRQ_STIP) -> IRQ_STIP.U
  ))

  val int_to_m = m_pending
  val chosen_cause = Mux(int_to_m, m_cause, s_cause)
  interrupt_cause := Cat(1.U(1.W), 0.U((dataBits - 1 - log2Ceil(16)).W), chosen_cause)

  val int_delegated = !int_to_m
  interrupt_target := Mux(int_delegated, stvec, mtvec)

  // ================================================================
  // Trap handling (exception + interrupt)
  // ================================================================
  val trap_cause_code = trap.bits.xcause(log2Ceil(16) - 1, 0).pad(log2Ceil(dataBits))
  val trap_delegated = Mux(
    trap.bits.is_interrupt,
    mideleg(trap_cause_code),
    medeleg(trap_cause_code)
  ) && (priv <= PRV_S.U)

  trap_target := Mux(trap_delegated, stvec, mtvec)

  when(trap.fire) {
    when(trap_delegated) {
      scause := trap.bits.xcause
      sepc := trap.bits.xepc
      stval := trap.bits.xtval
      val s1 = mstatus.bitSet(MSTATUS_SPP_BIT.U, priv(0))
      val s2 = s1.bitSet(MSTATUS_SPIE_BIT.U, mstatus(MSTATUS_SIE_BIT))
      val s3 = s2.bitSet(MSTATUS_SIE_BIT.U, false.B)
      mstatus := s3
      priv := PRV_S.U
    }.otherwise {
      mcause := trap.bits.xcause
      mepc := trap.bits.xepc
      mtval := trap.bits.xtval
      val s1 = (mstatus & ~(3.U(dataBits.W) << MSTATUS_MPP_LO.U)) | (priv << MSTATUS_MPP_LO.U)
      val s2 = s1.bitSet(MSTATUS_MPIE_BIT.U, mstatus(MSTATUS_MIE_BIT))
      val s3 = s2.bitSet(MSTATUS_MIE_BIT.U, false.B)
      mstatus := s3
      priv := PRV_M.U
    }
  }

  // ================================================================
  // MRET / SRET
  // ================================================================
  mepc_out := mepc
  sepc_out := sepc

  when(mret_event) {
    val mpp = mstatus(MSTATUS_MPP_HI, MSTATUS_MPP_LO)
    priv := mpp
    val s1 = mstatus.bitSet(MSTATUS_MIE_BIT.U, mstatus(MSTATUS_MPIE_BIT))
    val s2 = s1.bitSet(MSTATUS_MPIE_BIT.U, true.B)
    val s3 = s2 & ~(3.U(dataBits.W) << MSTATUS_MPP_LO.U)
    mstatus := s3
  }

  when(sret_event) {
    priv := Cat(0.U(1.W), mstatus(MSTATUS_SPP_BIT))
    val s1 = mstatus.bitSet(MSTATUS_SIE_BIT.U, mstatus(MSTATUS_SPIE_BIT))
    val s2 = s1.bitSet(MSTATUS_SPIE_BIT.U, true.B)
    val s3 = s2.bitSet(MSTATUS_SPP_BIT.U, false.B)
    mstatus := s3
  }

  // ================================================================
  // SFENCE.VMA pass-through
  // ================================================================
  sfence_vma_out := sfence_vma

  // ================================================================
  // SATP output (for MMU)
  // ================================================================
  satp_out := satp

  // ================================================================
  // CSR read mapping
  // ================================================================
  private val csr_map = Seq(
    // S-mode
    (SSTATUS.U,    sstatus_read),
    (SIE.U,        sie_read),
    (STVEC.U,      stvec),
    (SSCRATCH.U,   sscratch),
    (SEPC.U,       sepc),
    (SCAUSE.U,     scause),
    (STVAL.U,      stval),
    (SIP.U,        sip_read),
    (STIMECMP.U,   stimecmp),
    (SATP.U,       satp),
    // M-mode
    (MSTATUS.U,    mstatus),
    (MISA.U,       "h8000000000141101".U(dataBits.W)), // RV64IMASU
    (MEDELEG.U,    medeleg),
    (MIDELEG.U,    mideleg),
    (MIE.U,        mie),
    (MTVEC.U,      mtvec),
    (MCOUNTEREN.U, mcounteren),
    (MENVCFG.U,    menvcfg),
    (MSCRATCH.U,   mscratch),
    (MEPC.U,       mepc),
    (MCAUSE.U,     mcause),
    (MTVAL.U,      mtval),
    (MIP.U,        mip),
    (PMPCFG0.U,    pmpcfg0),
    (PMPADDR0.U,   pmpaddr0),
    (MVENDORID.U,  mvendorid),
    (MARCHID.U,    marchid),
    (MHARTID.U,    mhartid),
    // U-mode read-only
    (TIME.U,       mtime_in)
  )

  val csr_read = MuxLookup(late.bits.addr, 0.U)(csr_map)
  late.bits.result := csr_read

  // ================================================================
  // CSR write logic
  // ================================================================
  private val csrWdata = MuxCase(
    wdata,
    Seq(
      (late.bits.op === CSROpType.CSR_RW) -> wdata,
      (late.bits.op === CSROpType.CSR_RS) -> (csr_read | wdata),
      (late.bits.op === CSROpType.CSR_RC) -> (csr_read & ~wdata)
    )
  )

  when(late.bits.wen) {
    val addr = late.bits.addr
    // S-mode CSRs
    when(addr === SSTATUS.U)    { mstatus := (mstatus & ~sstatus_wmask) | (csrWdata & sstatus_wmask) }
    when(addr === SIE.U)        { mie := (mie & ~sie_mask) | (csrWdata & sie_mask) }
    when(addr === STVEC.U)      { stvec := csrWdata }
    when(addr === SSCRATCH.U)   { sscratch := csrWdata }
    when(addr === SEPC.U)       { sepc := csrWdata }
    when(addr === SCAUSE.U)     { scause := csrWdata }
    when(addr === STVAL.U)      { stval := csrWdata }
    when(addr === SIP.U)        { mip_reg_ssip := csrWdata(IRQ_SSIP) }
    when(addr === STIMECMP.U)   { stimecmp := csrWdata }
    when(addr === SATP.U)       { satp := csrWdata }
    // M-mode CSRs
    when(addr === MSTATUS.U)    { mstatus := (mstatus & mstatus_sxl_uxl_mask) | (csrWdata & ~mstatus_sxl_uxl_mask) }
    when(addr === MEDELEG.U)    { medeleg := csrWdata }
    when(addr === MIDELEG.U)    { mideleg := csrWdata }
    when(addr === MIE.U)        { mie := csrWdata }
    when(addr === MTVEC.U)      { mtvec := csrWdata }
    when(addr === MCOUNTEREN.U) { mcounteren := csrWdata }
    when(addr === MENVCFG.U)    { menvcfg := csrWdata }
    when(addr === MSCRATCH.U)   { mscratch := csrWdata }
    when(addr === MEPC.U)       { mepc := csrWdata }
    when(addr === MCAUSE.U)     { mcause := csrWdata }
    when(addr === MTVAL.U)      { mtval := csrWdata }
    when(addr === PMPCFG0.U)    { pmpcfg0 := csrWdata }
    when(addr === PMPADDR0.U)   { pmpaddr0 := csrWdata }
  }

  // ================================================================
  // Debug probe
  // ================================================================
  val csrDebugBundle = Wire(new CSRUDebugBundle)
  csrDebugBundle.mstatus := mstatus
  csrDebugBundle.mtvec := mtvec
  csrDebugBundle.mepc := mepc
  csrDebugBundle.mcause := mcause
  csrDebugBundle.mtval := mtval
  csrDebugBundle.mvendorid := mvendorid
  csrDebugBundle.marchid := marchid
  probe := csrDebugBundle
}
