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

  val MSTATUS = 0x0300 // 防止被意外符号拓展(scala只有signed int)
  val MTVEC = 0x0305
  val MEPC = 0x0341
  val MCAUSE = 0x0342
  val MTVAL = 0x0343
  val MCYCLE = 0x0b00
  val MCYCLEH = 0x0b80
  val MVENDORID = 0x0f11
  val MARCHID = 0x0f12
}

/** @brief
  *   有 core 的一些参数
  */
trait HasCoreParameter {
  val dataBits: Int = 32 // 机器字长
  val addrBits: Int = 32 // 寻址空间
  val instBits: Int = 32 // 指令字长
  val OpcodeBits: Int = 7
  val dataBytes = dataBits >> 3 // 一个 word 有几个字节  4
  val dataBytesBits = log2Ceil(dataBytes) // 一个 word 有几个字节的位宽 2
  val robEntryBits = 6 // 2^6 = 64
  val ghrBits: Int = 12 // log2Ceil(4096), matches BHT entries
}

trait HasSRAMParameter extends HasCoreParameter {
  val sramParams: SRAMBundleParameters = SRAMBundleParameters(
    addrBits = addrBits,
    dataBits = dataBits
  )
}

trait HasAXIParameter extends HasCoreParameter {
  val axiParams: AXI4BundleParameters = AXI4BundleParameters(
    addrBits = addrBits,
    dataBits = dataBits,
    idBits = ChipLinkParam.idBits
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
    val inSRAM = addr >= SoCConfig.sramBase.U && addr < (SoCConfig.sramBase + SoCConfig.sramSize).U
    val inSDRAM = addr >= SoCConfig.sdramBase.U && addr < (SoCConfig.sdramBase + SoCConfig.sdramSize).U
    val inMROM = addr >= SoCConfig.mromBase.U && addr < (SoCConfig.mromBase + SoCConfig.mromSize).U
    val inFlash = addr >= SoCConfig.xipFlashBase.U && addr < (SoCConfig.xipFlashBase + SoCConfig.xipFlashSize).U
    !(inSRAM || inSDRAM || inMROM || inFlash)
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
