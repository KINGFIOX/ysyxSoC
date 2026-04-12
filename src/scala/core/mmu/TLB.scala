package ysyx.core.mmu

import chisel3._
import chisel3.util._

import ysyx.core.common._

class TLBEntry extends Bundle {
  val valid = Bool()
  val vpn   = UInt(27.W) // Sv39: 27-bit VPN (VPN[2]:VPN[1]:VPN[0])
  val ppn   = UInt(44.W)
  val flags = UInt(8.W)  // D A G U X W R V
  val level = UInt(2.W)  // 0=4KB, 1=2MB megapage, 2=1GB gigapage
}

class TLBLookupReq extends Bundle {
  val vpn = UInt(27.W)
}

class TLBLookupResp extends Bundle {
  val hit   = Bool()
  val ppn   = UInt(44.W)
  val flags = UInt(8.W)
  val level = UInt(2.W)
}

class TLBRefill extends Bundle {
  val valid = Bool()
  val vpn   = UInt(27.W)
  val ppn   = UInt(44.W)
  val flags = UInt(8.W)
  val level = UInt(2.W)
}

class TLB(numEntries: Int = 16) extends Module {
  val io = IO(new Bundle {
    val lookup = new Bundle {
      val req  = Input(new TLBLookupReq)
      val resp = Output(new TLBLookupResp)
    }
    val refill = Input(new TLBRefill)
    val flush  = Input(Bool())
  })

  val entries = RegInit(VecInit(Seq.fill(numEntries)(0.U.asTypeOf(new TLBEntry))))
  val replacePtr = RegInit(0.U(log2Ceil(numEntries).W))

  // Lookup: check all entries
  val vpn_req = io.lookup.req.vpn
  val hits = VecInit(entries.map { e =>
    val match_4k = e.valid && (e.vpn === vpn_req) && (e.level === 0.U)
    val match_2m = e.valid && (e.vpn(26, 9) === vpn_req(26, 9)) && (e.level === 1.U)
    val match_1g = e.valid && (e.vpn(26, 18) === vpn_req(26, 18)) && (e.level === 2.U)
    match_4k || match_2m || match_1g
  })

  val hit = hits.asUInt.orR
  val hitIdx = PriorityEncoder(hits.asUInt)
  val hitEntry = entries(hitIdx)

  // For megapages/gigapages, the lower VPN bits become part of the PA
  val result_ppn = MuxCase(hitEntry.ppn, Seq(
    (hitEntry.level === 1.U) -> Cat(hitEntry.ppn(43, 9), vpn_req(8, 0)),
    (hitEntry.level === 2.U) -> Cat(hitEntry.ppn(43, 18), vpn_req(17, 0))
  ))

  io.lookup.resp.hit := hit
  io.lookup.resp.ppn := result_ppn
  io.lookup.resp.flags := hitEntry.flags
  io.lookup.resp.level := hitEntry.level

  // Refill
  when(io.refill.valid) {
    val e = entries(replacePtr)
    e.valid := true.B
    e.vpn := io.refill.vpn
    e.ppn := io.refill.ppn
    e.flags := io.refill.flags
    e.level := io.refill.level
    replacePtr := replacePtr + 1.U
  }

  // Flush
  when(io.flush) {
    entries.foreach(_.valid := false.B)
  }
}
