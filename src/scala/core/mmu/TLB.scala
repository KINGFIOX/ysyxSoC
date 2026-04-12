package ysyx.core.mmu

import chisel3._
import chisel3.util._

import ysyx.core.common._

class TLBEntry extends Bundle {
  val valid = Bool()
  val vpn   = UInt(27.W)
  val ppn   = UInt(44.W)
  val flags = UInt(8.W)  // D A G U X W R V
}

class TLBLookupReq extends Bundle {
  val vpn = UInt(27.W)
}

class TLBLookupResp extends Bundle {
  val hit   = Bool()
  val ppn   = UInt(44.W)
  val flags = UInt(8.W)
}

class TLBRefill extends Bundle {
  val valid = Bool()
  val vpn   = UInt(27.W)
  val ppn   = UInt(44.W)
  val flags = UInt(8.W)
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

  val vpn_req = io.lookup.req.vpn
  val hits = VecInit(entries.map { e =>
    e.valid && (e.vpn === vpn_req)
  })

  val hit = hits.asUInt.orR
  val hitIdx = PriorityEncoder(hits.asUInt)
  val hitEntry = entries(hitIdx)

  io.lookup.resp.hit := hit
  io.lookup.resp.ppn := hitEntry.ppn
  io.lookup.resp.flags := hitEntry.flags

  when(io.refill.valid) {
    val e = entries(replacePtr)
    e.valid := true.B
    e.vpn := io.refill.vpn
    e.ppn := io.refill.ppn
    e.flags := io.refill.flags
    replacePtr := replacePtr + 1.U
  }

  when(io.flush) {
    entries.foreach(_.valid := false.B)
  }
}
