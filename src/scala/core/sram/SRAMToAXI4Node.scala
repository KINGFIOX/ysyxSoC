package ysyx.core.sram

import org.chipsalliance.diplomacy.ValName
import org.chipsalliance.diplomacy.nodes.MixedAdapterNode

import freechips.rocketchip.amba.axi4.{
  AXI4Imp,
  AXI4MasterPortParameters,
  AXI4MasterParameters
}
import freechips.rocketchip.diplomacy.IdRange

case class SRAMToAXI4Node()(implicit valName: ValName)
    extends MixedAdapterNode(SRAMImp, AXI4Imp)(
      dFn = { mp =>
        AXI4MasterPortParameters(
          masters = mp.masters.map { m =>
            AXI4MasterParameters(
              name = m.name,
              id = IdRange(0, 1),
              nodePath = m.nodePath
            )
          }
        )
      },
      uFn = { sp =>
        SRAMSlavePortParameters(
          slaves = sp.slaves.map { s =>
            SRAMSlaveParameters(
              address = s.address,
              resources = s.resources,
              regionType = s.regionType,
              executable = s.executable,
              nodePath = s.nodePath,
              supportsRead = s.supportsRead.max > 0,
              supportsWrite = s.supportsWrite.max > 0
            )
          },
          beatBytes = sp.beatBytes
        )
      }
    )
