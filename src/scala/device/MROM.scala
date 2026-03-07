package ysyx

import chisel3._
import chisel3.util._

import freechips.rocketchip.amba.axi4._
import org.chipsalliance.cde.config.Parameters
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.util._
import scopt.Read

class MROMHelper
    extends FixedIOExtModule(new Bundle {
      val raddr = Input(UInt(32.W))
      val ren = Input(Bool())
      val rdata = Output(UInt(32.W))
    }) {
  setInline(
    "MROMHelper.sv",
    """module MROMHelper(
      |  input [31:0] raddr,
      |  input ren,
      |  output reg [31:0] rdata
      |);
      |import "DPI-C" function void mrom_read(input int raddr, output int rdata);
      |always @(*) begin
      |  if (ren) mrom_read(raddr, rdata);
      |  else rdata = 0;
      |end
      |endmodule
    """.stripMargin
  )
}

class AXI4MROM(address: Seq[AddressSet])(implicit p: Parameters)
    extends LazyModule {
  val beatBytes = 4
  val node = AXI4SlaveNode(
    Seq(
      AXI4SlavePortParameters(
        Seq(
          AXI4SlaveParameters(
            address = address,
            executable = true,
            supportsWrite = TransferSizes.none,
            supportsRead = TransferSizes(1, beatBytes),
            interleavedId = Some(0)
          )
        ),
        beatBytes = beatBytes
      )
    )
  )

  lazy val module = new Impl
  class Impl extends LazyModuleImp(this) {
    val (in, _) = node.in(0)
    val (ar, r, aw, w, b) = (in.ar, in.r, in.aw, in.w, in.b)
    val mrom = Module(new MROMHelper)

// -- read state machine --------------------------------------
    object ReadState extends ChiselEnum {
      val idle, waitReady = Value
    }
    val read_state = RegInit(ReadState.idle)
    read_state := Mux(
      read_state === ReadState.idle,
      Mux(ar.fire, ReadState.waitReady, ReadState.idle),
      Mux(r.fire, ReadState.idle, ReadState.waitReady)
    )
    mrom.io.raddr := ar.bits.addr
    mrom.io.ren := ar.fire
    ar.ready := (read_state === ReadState.idle)
    r.bits.data := RegEnable(mrom.io.rdata, ar.fire)
    r.bits.id := RegEnable(ar.bits.id, ar.fire)
    r.bits.resp := 0.U
    r.bits.last := true.B
    r.valid := (read_state === ReadState.waitReady)
// -- write state machine -------------------------------------
    object WriteState extends ChiselEnum {
      val idle, done = Value
    }

    private val write_state = RegInit(WriteState.idle)
    private val aw_received = RegInit(false.B)
    private val w_received = RegInit(false.B)

    aw.ready := (write_state === WriteState.idle) && !aw_received
    w.ready := (write_state === WriteState.idle) && !w_received
    b.valid := (write_state === WriteState.done)
    b.bits.resp := AXI4Parameters.RESP_DECERR

    switch(write_state) {
      is(WriteState.idle) {
        when(aw.fire) { aw_received := true.B }
        when(w.fire) { w_received := true.B }
        val aw_done = aw_received || aw.fire
        val w_done = w_received || w.fire
        when(aw_done && w_done) {
          write_state := WriteState.done
          aw_received := false.B
          w_received := false.B
        }
      }
      is(WriteState.done) {
        when(b.fire) { write_state := WriteState.idle }
      }
    }
  }
}
