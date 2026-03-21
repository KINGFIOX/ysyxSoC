package ysyx.core.frontend

import chisel3._
import chisel3.util._
import ysyx.core.common._

// branch target buffer
class BTBUpdate extends NPCBundle {
}

// branch history table
class BHTUpdate extends NPCBundle {
  val br_flag = Bool()
}

// from the master's view of point
class RASUpdate extends NPCBundle {
  val is_call = Bool()
  val is_ret = Bool()
}
