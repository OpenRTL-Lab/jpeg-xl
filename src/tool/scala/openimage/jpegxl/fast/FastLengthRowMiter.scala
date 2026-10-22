// OpenRTL Lab | JPEG XL | version 1.0.0
// SPDX-License-Identifier: MIT
// SPDX-License-Identifier: MIT
package openimage.jpegxl.fast

import chisel3._
import chisel3.util._
import _root_.circt.stage.ChiselStage

/** Bounded hardware-to-hardware structural comparison, NOT a codec oracle.
  * The legacy expression below is the pre-optimization row network; functional
  * acceptance separately requires the fixed official libjxl vectors/files.
  */
class FastLengthRowMiter(negative: Boolean) extends Module {
  val io = IO(new Bundle { val finished = Output(Bool()) })
  val cycle = RegInit(0.U(16.W)); cycle := cycle + 1.U
  val random = RegInit("h1c357a91".U(32.W))
  random := Cat(random(30, 0), random(31) ^ random(21) ^ random(1) ^ random(0))

  for ((lanes, maxPrecision) <- Seq((64, 14), (32, 5))) {
    val bankBits = log2Ceil(lanes)
    val rows = (1 << maxPrecision) / lanes + 1
    val copies = 1 + math.max(1, maxPrecision - bankBits)
    val phase = cycle / (14 * 257).U
    val precision = Wire(UInt(4.W)); precision := cycle % maxPrecision.U + 1.U
    val row = Wire(UInt(log2Ceil(rows).W)); row := (cycle / maxPrecision.U) % rows.U
    val minimum = Wire(UInt(4.W))
    minimum := Mux(phase === 0.U, 1.U, (cycle / 5.U) % maxPrecision.U + 1.U)
    val maximum = Wire(UInt(4.W))
    maximum := Mux(phase === 1.U, maxPrecision.U, (cycle / 7.U) % maxPrecision.U + 1.U)
    val frequency = Mux(phase === 1.U, 0.U(18.W), random(17, 0))
    val infinity = Mux(phase === 2.U, random(23, 0), 4755548.U(24.W))
    val target = (1.U((maxPrecision + 1).W) << precision)(maxPrecision, 0)
    val reads = (0 until copies).map { copy =>
      VecInit((0 until lanes).map { bank =>
        val mixed = random(23, 0) ^ ((copy * 977 + bank * 7919 + 17) & 0xffffff).U(24.W)
        Mux(phase === 1.U, 0.U(24.W), mixed)
      })
    }
    val dut = Module(new FastLengthRow(lanes, maxPrecision))
    val latency = 3+log2Ceil(maxPrecision)
    val inputValid = cycle < (4*14*257).U
    dut.clock := clock; dut.rst_n := !reset.asBool; dut.io.inputValid := inputValid
    dut.io.reads := VecInit(reads.map(_.asUInt))
    dut.io.precision := precision; dut.io.minimum := minimum; dut.io.maximum := maximum
    dut.io.weighted := VecInit((1 to maxPrecision).map { bits =>
      val weighted = Wire(UInt(24.W)); weighted := frequency*bits.U; weighted
    })
    dut.io.infinity := infinity
    dut.io.target := target; dut.io.row := row

    // Frozen old two-step selection: MuxCase by precision, THEN dynamic bank.
    val values = Wire(Vec(maxPrecision, Vec(lanes, UInt(24.W))))
    for (k <- 0 until maxPrecision; p <- 0 until lanes) {
      val options = (0 until maxPrecision).map { exponent =>
        val value = if (exponent < bankBits)
          reads(if (p + (1 << exponent) >= lanes) 1 else 0)(p)
        else reads(1 + exponent - bankBits)(p)
        (precision === (k + 1 + exponent).U, value)
      }
      values(k)(p) := MuxCase(0.U(24.W), options)
    }
    def legacyTree(xs: Seq[(UInt, UInt)]): (UInt, UInt) = {
      if (xs.size == 1) xs.head
      else legacyTree(xs.grouped(2).map { pair =>
        if (pair.size == 1) pair.head
        else {
          val a = pair(0); val b = pair(1)
          val takeA = a._1 < b._1 || a._1 === b._1 && a._2 <= b._2
          (Mux(takeA, a._1, b._1), Mux(takeA, a._2, b._2))
        }
      }.toSeq)
    }
    val actualCosts = dut.io.costs.asTypeOf(Vec(lanes, UInt(24.W)))
    val actualLengths = dut.io.lengths.asTypeOf(Vec(lanes, UInt(4.W)))
    for (b <- 0 until lanes) {
      val offset = (row << bankBits) + b.U
      val candidates = (0 until maxPrecision).map { k =>
        val bits = k + 1
        val delta = (1.U((maxPrecision + 1).W) << (precision - bits.U))(maxPrecision, 0)
        val bank = (b.U - delta(bankBits - 1, 0))(bankBits - 1, 0)
        val sum = values(k)(bank) +& frequency * bits.U
        val valid = bits.U <= precision && bits.U >= minimum && bits.U <= maximum &&
          offset <= target && offset >= delta
        (Mux(valid && sum < infinity, sum(23, 0), infinity), bits.U(4.W))
      }
      val expected = legacyTree(candidates)
      val corrupt = if (negative && lanes == 64 && b == 0) cycle === 30.U else false.B
      val expectedCost = ShiftRegister(expected._1 ^ corrupt.asUInt, latency)
      val expectedLength = ShiftRegister(expected._2, latency)
      when(dut.io.outputValid) {
        assert(actualCosts(b) === expectedCost, "FAST_ROW_MITER_COST")
        assert(actualLengths(b) === expectedLength, "FAST_ROW_MITER_LENGTH")
      }
    }
    assert(dut.io.outputValid === ShiftRegister(inputValid,latency,false.B,true.B),
      "FAST_ROW_MITER_VALID")
  }
  val last = cycle === (4 * 14 * 257 - 1 + 7).U
  when(last) { printf("FAST_ROW_MITER_PASS cycles=14399 configurations=2 lane_comparisons=1381632\n") }
  io.finished := last
}

object EmitFastLengthRowMiter {
  def main(args: Array[String]): Unit = {
    require(args.length == 2)
    ChiselStage.emitSystemVerilogFile(new FastLengthRowMiter(args(1) == "negative"),
      Array("--target-dir", args(0)),
      Array("--split-verilog", "--disable-all-randomization", "--strip-debug-info"))
  }
}
