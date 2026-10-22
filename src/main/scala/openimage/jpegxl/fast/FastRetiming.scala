// OpenRTL Lab | JPEG XL | version 1.0.0
// SPDX-License-Identifier: MIT
// SPDX-License-Identifier: MIT
package openimage.jpegxl.fast

import chisel3._
import chisel3.util._

/** Fixed-width operators with explicit register boundaries, not timing exceptions. */
private[fast] object FastRetiming {
  // Full-width comparison is intentional: length==width must not wrap to zero.
  def mask(length: UInt, width: Int): UInt = {
    require(width > 0)
    VecInit((0 until width).map(i => length > i.U)).asUInt
  }

  /** Every binary level, including an unpaired node, crosses a register.
    * The caller pulses launch once and keeps the command stable until valid.
    * No data reset is needed: reset clears every validity stage.
    */
  def tree(values: Seq[UInt], launch: Bool, width: Int, name: String)
      (combine: (UInt, UInt) => UInt): (UInt, Bool) = {
    require(values.nonEmpty && width > 0)
    // Widen BEFORE the first add. In Chisel a+b keeps the operand width;
    // assigning a one-bit sum into a wide register cannot recover its carry.
    var nodes = values.map { input => val node = Wire(UInt(width.W)); node := input; node }
    var valid = launch
    var level = 0
    while (nodes.size > 1) {
      val enable = valid
      nodes = nodes.grouped(2).zipWithIndex.map { case (pair, index) =>
        val value = Wire(UInt(width.W))
        value := (if (pair.size == 2) combine(pair.head, pair(1)) else pair.head)
        RegEnable(value, enable).suggestName(s"${name}${level}_$index")
      }.toSeq
      valid = RegNext(enable, false.B).suggestName(s"${name}${level}Valid")
      level += 1
    }
    (nodes.head, valid)
  }
}
