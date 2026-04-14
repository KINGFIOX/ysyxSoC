package ysyx.core.common

import chisel3._
import chisel3.util._

import freechips.rocketchip.amba.axi4.AXI4BundleParameters

import ysyx.core.sram.SRAMBundleParameters
import ysyx.device._
import ysyx.SoCConfig

/** @brief
  *   有几个通用寄存器
  */
trait HasRegFileParameter {
  val NRReg = 32
  val NRRegbits = log2Up(NRReg)
}

trait HasCSRParameter {
  val NRCSR = 0x1000
  val NRCSRbits = log2Up(NRCSR)

  // S-mode CSRs
  val SSTATUS    = 0x0100
  val SIE        = 0x0104
  val STVEC      = 0x0105
  val SCOUNTEREN = 0x0106
  val SSCRATCH   = 0x0140
  val SEPC       = 0x0141
  val SCAUSE     = 0x0142
  val STVAL      = 0x0143
  val SIP        = 0x0144
  val STIMECMP   = 0x014D // Sstc extension
  val SATP       = 0x0180

  // M-mode CSRs (use hex to avoid sign extension in Scala's signed Int)
  val MSTATUS    = 0x0300
  val MISA       = 0x0301
  val MEDELEG    = 0x0302
  val MIDELEG    = 0x0303
  val MIE        = 0x0304
  val MTVEC      = 0x0305
  val MCOUNTEREN = 0x0306
  val MENVCFG    = 0x030A
  val MSCRATCH   = 0x0340
  val MEPC       = 0x0341
  val MCAUSE     = 0x0342
  val MTVAL      = 0x0343
  val MIP        = 0x0344
  val PMPCFG0    = 0x03A0
  val PMPADDR0   = 0x03B0
  val MCYCLE     = 0x0b00
  val MCYCLEH    = 0x0b80
  val MVENDORID  = 0x0f11
  val MARCHID    = 0x0f12
  val MHARTID    = 0x0f14

  // U-mode / read-only CSRs
  val TIME       = 0x0C01

  // Privilege levels
  val PRV_U = 0
  val PRV_S = 1
  val PRV_M = 3

  // mstatus bit positions
  val MSTATUS_SIE_BIT  = 1
  val MSTATUS_MIE_BIT  = 3
  val MSTATUS_SPIE_BIT = 5
  val MSTATUS_MPIE_BIT = 7
  val MSTATUS_SPP_BIT  = 8
  val MSTATUS_MPP_LO   = 11
  val MSTATUS_MPP_HI   = 12
  val MSTATUS_SUM_BIT  = 18
  val MSTATUS_MXR_BIT  = 19

  // sstatus read mask: bits visible through sstatus view of mstatus
  val SSTATUS_MASK: Long = (1L << 1) | (1L << 5) | (1L << 8) |
    (1L << 18) | (1L << 19) | // SIE, SPIE, SPP, SUM, MXR
    (3L << 32)                // UXL (bits 33:32, read-only in sstatus)

  // sstatus write mask: writable bits only (UXL is read-only)
  val SSTATUS_WMASK: Long = (1L << 1) | (1L << 5) | (1L << 8) |
    (1L << 18) | (1L << 19) // SIE, SPIE, SPP, SUM, MXR

  // mstatus read-only mask: SXL (bits 35:34) and UXL (bits 33:32) are WARL, hardwired to 2 for RV64
  val MSTATUS_SXL_UXL: Long = (3L << 32) | (3L << 34) // bits 35:32

  // mip/mie bit positions
  val IRQ_SSIP = 1
  val IRQ_MSIP = 3
  val IRQ_STIP = 5
  val IRQ_MTIP = 7
  val IRQ_SEIP = 9
  val IRQ_MEIP = 11

  // sie/sip mask: bits visible through sie/sip view
  val SIE_MASK: Long = (1L << IRQ_SSIP) | (1L << IRQ_STIP) | (1L << IRQ_SEIP)
}

/** @brief
  *   有 core 的一些参数
  */
trait HasCoreParameter {
  val dataBits: Int = 64
  val addrBits: Int = 64
  val busAddrBits: Int = 64
  val instBits: Int = 32
  val OpcodeBits: Int = 7
  val dataBytes = dataBits >> 3 // 一个 word 有几个字节  4
  val dataBytesBits = log2Ceil(dataBytes) // 一个 word 有几个字节的位宽 2
  val robEntryBits = 6 // 2^6 = 64
  val ghrBits: Int = 12 // log2Ceil(4096), matches BHT entries. global history register
  val NRPhyReg: Int = 64
  val NRPhyRegBits: Int = log2Up(NRPhyReg) // = 6
}

trait HasSRAMParameter extends HasCoreParameter {
  val sramParams: SRAMBundleParameters = SRAMBundleParameters(
    addrBits = busAddrBits,
    dataBits = dataBits
  )
}

trait HasAXIParameter extends HasCoreParameter {
  val axiParams: AXI4BundleParameters = AXI4BundleParameters(
    addrBits = busAddrBits,
    dataBits = dataBits,
    idBits = 4,
  )
}

abstract class NPCModule
    extends Module
    with HasRegFileParameter
    with HasCoreParameter
    with HasCSRParameter
    with HasAXIParameter
    with HasSRAMParameter

// format: off
object AddressMap {
  def is_mmio(addr: UInt): Bool = {
    val inSDRAM = addr >= SoCConfig.sdramBase.U && addr < (SoCConfig.sdramBase + SoCConfig.sdramSize).U
    val inFlash = addr >= SoCConfig.xipFlashBase.U && addr < (SoCConfig.xipFlashBase + SoCConfig.xipFlashSize).U
    !(inSDRAM || inFlash)
  }
}
// format: on

abstract class NPCBundle
    extends Bundle
    with HasRegFileParameter
    with HasCoreParameter
    with HasCSRParameter
    with HasAXIParameter
    with HasSRAMParameter
