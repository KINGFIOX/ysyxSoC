package ysyx.core.sram

import chisel3.util._
import chisel3.experimental.SourceInfo

import scala.math.max
import org.chipsalliance.cde.config.Parameters
import org.chipsalliance.diplomacy.nodes.BaseNode
import freechips.rocketchip.diplomacy.{AddressSet, RegionType}
import freechips.rocketchip.resources.Resource

case class SRAMBundleParameters(
    addrBits: Int,
    dataBits: Int
) {
  require(dataBits >= 8)
  require(addrBits >= 1)
  require(isPow2(dataBits))

  val maskBits = dataBits / 8

  def union(x: SRAMBundleParameters) = SRAMBundleParameters(
    max(addrBits, x.addrBits),
    max(dataBits, x.dataBits)
  )
}

object SRAMBundleParameters {
  val emptyBundleParams = SRAMBundleParameters(addrBits = 1, dataBits = 8)

  def union(x: Seq[SRAMBundleParameters]) =
    x.foldLeft(emptyBundleParams)((a, b) => a.union(b))

  def apply(master: SRAMMasterPortParameters, slave: SRAMSlavePortParameters) =
    new SRAMBundleParameters(
      addrBits = log2Up(slave.maxAddress + 1),
      dataBits = slave.beatBytes * 8
    )
}

case class SRAMMasterParameters(
    name: String,
    nodePath: Seq[BaseNode] = Seq()
)

case class SRAMMasterPortParameters(
    masters: Seq[SRAMMasterParameters]
)

case class SRAMSlaveParameters(
    address: Seq[AddressSet],
    resources: Seq[Resource] = Nil,
    regionType: RegionType.T = RegionType.GET_EFFECTS,
    executable: Boolean = false,
    nodePath: Seq[BaseNode] = Seq(),
    supportsRead: Boolean = true,
    supportsWrite: Boolean = true
) {
  address.foreach { a => require(a.finite) }
  address.combinations(2).foreach { case Seq(x, y) => require(!x.overlaps(y)) }

  val name =
    nodePath.lastOption.map(_.lazyModule.name).getOrElse("disconnected")
  val maxAddress = address.map(_.max).max
}

case class SRAMSlavePortParameters(
    slaves: Seq[SRAMSlaveParameters],
    beatBytes: Int
) {
  require(slaves.nonEmpty)
  require(isPow2(beatBytes))

  val maxAddress = slaves.map(_.maxAddress).max

  // such as
  // slaves: [a, b, c]
  // slaves.combinations(2): [ Seq(a, b), Seq(a, c), Seq(b, c) ]
  slaves.combinations(2).foreach { case Seq(x, y) =>
    x.address.foreach { a =>
      y.address.foreach { b =>
        require(!a.overlaps(b))
      }
    }
  }
}

case class SRAMEdgeParameters(
    master: SRAMMasterPortParameters,
    slave: SRAMSlavePortParameters,
    params: Parameters,
    sourceInfo: SourceInfo
) {
  val bundle = SRAMBundleParameters(master, slave)
}
