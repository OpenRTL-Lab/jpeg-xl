// OpenRTL Lab | JPEG XL | version 1.0.0
// SPDX-License-Identifier: MIT
// SPDX-License-Identifier: MIT
package openimage.jpegxl
import chisel3._
import chisel3.util._

/** Declarative standard fields, not a reference-generated file or image ROM. */
object FieldGrammar {
  type Field = (UInt, UInt)
  private def f(n: Int, v: Int): Field = (n.U(6.W), v.U(32.W))
  private def dynamic(n: Int, v: UInt): Field = (n.U(6.W), Cat(0.U(32.W), v)(31, 0))
  private def align(count: UInt): Field = ((0.U(4.W)-count(2, 0))(2, 0).pad(6), 0.U(32.W))

  def group(predictor: Bool): Seq[Field] = {
    val global = Seq(f(1, 1), f(1, 0), f(1, 0), f(1, 1), f(2, 0))
    // Local tree: no LZ, all six contexts clustered, prefix/direct integers,
    // alphabet 2, two one-bit symbols, one leaf/unit multiplier/zero offset.
    val tree = Seq(f(1, 0), f(1, 1), f(2, 0), f(1, 1), f(4, 15),
      f(1, 1), f(4, 0), f(2, 1), f(2, 1), f(1, 0), f(1, 1),
      f(1, 0), dynamic(1, predictor), f(1, 0), f(1, 0), f(1, 0))
    // Pixel entropy: hybrid (2,0,1), alphabet18. Two code-length symbols
    // declare fourteen four-bit and four five-bit canonical codes.
    val pixel = Seq(f(1, 0), f(1, 1), f(4, 2), f(2, 0), f(2, 1),
      f(1, 1), f(4, 4), f(4, 1), f(2, 0))
    val order = Seq(1, 2, 3, 4, 0, 5, 17, 6, 16, 7, 8, 9, 10, 11, 12, 13, 14, 15)
    global ++ tree ++ pixel ++ order.take(6).map(s => if (s == 4 || s == 5) f(4, 7) else f(2, 0)) ++
      Seq(f(14,0),f(4,15))
  }

  def header(cmd: Command, groupBytes: UInt, bitCount: UInt): Seq[Field] = {
    val alpha = cmd.channels === 2.U || cmd.channels === 4.U
    val gray = cmd.channels <= 2.U
    val image = Seq(f(16, 0x0aff), f(1, 0), f(2, 0), dynamic(9, cmd.imageHeight-1.U),
      f(3, 0), f(2, 0), dynamic(9, cmd.imageWidth-1.U), f(1, 0), f(1, 0),
      f(1, 0), f(2, 0), f(1, 1), dynamic(2, alpha),
      (Mux(alpha, 1.U, 0.U).pad(6), 1.U(32.W)), f(1, 0), dynamic(1, !gray))
    val grayFields = Seq(f(1, 0), f(2, 1), f(2, 1), f(1, 0), f(2, 2), f(4, 11), f(2, 1))
      .map { case (n, v) => (Mux(gray, n, 0.U), v) }
    val imageEnd = Seq(f(2, 0), f(1, 1), align(bitCount))
    val frame = Seq(f(1, 0), f(2, 0), f(1, 1), f(2, 0), f(1, 0), f(2, 0),
      (Mux(alpha, 2.U, 0.U).pad(6), 0.U(32.W)), f(2, 1), f(2, 0), f(1, 0), f(2, 0),
      (Mux(alpha, 2.U, 0.U).pad(6), 0.U(32.W)), f(1, 1), f(2, 0),
      f(1, 0), f(1, 0), f(2, 0), f(2, 0), f(2, 0), f(1, 0), align(bitCount))
    val small = groupBytes < 1024.U
    val medium = groupBytes < 17408.U
    val toc = Seq(dynamic(2, Mux(small, 0.U, Mux(medium, 1.U, 2.U))),
      (Mux(small, 10.U, Mux(medium, 14.U, 22.U)).pad(6),
       Mux(small, groupBytes, Mux(medium, groupBytes-1024.U, groupBytes-17408.U)).pad(32)), align(bitCount))
    image ++ grayFields ++ imageEnd ++ frame ++ toc
  }
}
