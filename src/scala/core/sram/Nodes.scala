package ysyx.core.sram

import chisel3.experimental.SourceInfo

import org.chipsalliance.cde.config.Parameters
import org.chipsalliance.diplomacy.ValName
import org.chipsalliance.diplomacy.nodes.{
  SimpleNodeImp,
  RenderedEdge,
  InwardNode,
  OutwardNode,
  SourceNode,
  SinkNode,
  NexusNode,
  IdentityNode
}

object SRAMImp
    extends SimpleNodeImp[
      SRAMMasterPortParameters,
      SRAMSlavePortParameters,
      SRAMEdgeParameters,
      SRAMBundle
    ] {
  def edge(
      pd: SRAMMasterPortParameters,
      pu: SRAMSlavePortParameters,
      p: Parameters,
      sourceInfo: SourceInfo
  ) = SRAMEdgeParameters(pd, pu, p, sourceInfo)

  def bundle(e: SRAMEdgeParameters) = SRAMBundle(e.bundle)

  def render(e: SRAMEdgeParameters) =
    RenderedEdge(
      colour = "#ff9900" /* orange */,
      (e.slave.beatBytes * 8).toString
    )

  override def mixO(
      pd: SRAMMasterPortParameters,
      node: OutwardNode[
        SRAMMasterPortParameters,
        SRAMSlavePortParameters,
        SRAMBundle
      ]
  ): SRAMMasterPortParameters =
    pd.copy(masters =
      pd.masters.map(c => c.copy(nodePath = node +: c.nodePath))
    )

  override def mixI(
      pu: SRAMSlavePortParameters,
      node: InwardNode[
        SRAMMasterPortParameters,
        SRAMSlavePortParameters,
        SRAMBundle
      ]
  ): SRAMSlavePortParameters =
    pu.copy(slaves = pu.slaves.map(m => m.copy(nodePath = node +: m.nodePath)))
}

case class SRAMMasterNode(portParams: Seq[SRAMMasterPortParameters])(implicit
    valName: ValName
) extends SourceNode(SRAMImp)(portParams)

case class SRAMSlaveNode(portParams: Seq[SRAMSlavePortParameters])(implicit
    valName: ValName
) extends SinkNode(SRAMImp)(portParams)

case class SRAMNexusNode(
    masterFn: Seq[SRAMMasterPortParameters] => SRAMMasterPortParameters,
    slaveFn: Seq[SRAMSlavePortParameters] => SRAMSlavePortParameters
)(implicit valName: ValName)
    extends NexusNode(SRAMImp)(masterFn, slaveFn)

case class SRAMIdentityNode()(implicit valName: ValName)
    extends IdentityNode(SRAMImp)()
