package ysyx.device

import chisel3._

class ExternalPins extends Bundle {
  val gpio = new GPIOIO
  val ps2 = new PS2IO
  val vga = new VGAIO
}
