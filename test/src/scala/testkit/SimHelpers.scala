package ysyx.testkit

object SimHelpers {
  def stepN(n: Int)(step: => Unit): Unit = {
    require(n >= 0)
    var i = 0
    while (i < n) {
      step
      i += 1
    }
  }

  def stepUntil(
      predicate: => Boolean,
      maxCycles: Int,
      clue: String
  )(step: => Unit): Unit = {
    var cycles = 0
    while (!predicate && cycles < maxCycles) {
      step
      cycles += 1
    }
    assert(predicate, s"Timeout waiting for condition: $clue")
  }

  def pte(ppn: BigInt, flags: Int): BigInt = {
    require((flags & ~0xff) == 0, "flags must be 8-bit")
    (ppn << 10) | BigInt(flags & 0xff)
  }
}
