// OpenRTL Lab | JPEG XL | version 1.0.0
// SPDX-License-Identifier: MIT
// SPDX-License-Identifier: MIT
package openimage.jpegxl.fast

import chisel3._
import chisel3.util._

/** One offset's exact candidate reduction. All predecessor ports are already
  * statically routed; there is deliberately no variable bank index here.
  */
class FastLengthLane(val maxPrecision: Int) extends RawModule {
  val clock = IO(Input(Clock())); val rst_n = IO(Input(Bool()))
  val io = IO(new Bundle {
    val inputValid = Input(Bool()); val outputValid = Output(Bool())
    val predecessors = Input(Vec(maxPrecision, UInt(24.W)))
    val weighted = Input(Vec(maxPrecision, UInt(24.W)))
    val precisionOneHot = Input(UInt(maxPrecision.W))
    val reachable = Input(UInt(maxPrecision.W))
    val allowed = Input(UInt(maxPrecision.W))
    val inRange = Input(Bool())
    val infinity = Input(UInt(24.W))
    val cost = Output(UInt(24.W))
    val length = Output(UInt(4.W))
  })
  withClockAndReset(clock, (!rst_n).asAsyncReset) {
  val selectorsByLength = (0 until maxPrecision).map { k =>
    val selectors = (0 until maxPrecision - k).map { exponent =>
      io.precisionOneHot(k + exponent) && io.reachable(exponent)
    }
    selectors
  }
  val previous = selectorsByLength.zipWithIndex.map { case (selectors,k) =>
    RegEnable(Mux1H(selectors, io.predecessors.take(selectors.size)), io.inputValid)
      .suggestName(s"selectedPrevious$k")
  }
  val selectedValid = RegEnable(VecInit((0 until maxPrecision).map(k =>
    io.inRange && io.allowed(k) && selectorsByLength(k).reduce(_ || _))), io.inputValid)
  val selectedWeights = RegEnable(io.weighted, io.inputValid)
  val selectedInfinity = RegEnable(io.infinity, io.inputValid)
  val selectionValid = RegNext(io.inputValid, false.B)
  val sums = Wire(Vec(maxPrecision, UInt(25.W)))
  for (k <- 0 until maxPrecision by 2) {
    val adder = Module(new FastFpgaAdd)
    adder.io.a0 := previous(k); adder.io.b0 := selectedWeights(k)
    adder.io.a1 := (if (k + 1 < maxPrecision) previous(k + 1) else 0.U(24.W))
    adder.io.b1 := (if (k + 1 < maxPrecision) selectedWeights(k + 1) else 0.U(24.W))
    sums(k) := RegEnable(adder.io.s0, selectionValid).suggestName(s"candidateSum$k")
    if (k + 1 < maxPrecision)
      sums(k + 1) := RegEnable(adder.io.s1, selectionValid).suggestName(s"candidateSum${k+1}")
  }
  val sumValid = RegNext(selectionValid, false.B)
  val sumAllowed = RegEnable(selectedValid, selectionValid)
  val sumInfinity = RegEnable(selectedInfinity, selectionValid)
  val candidates = (0 until maxPrecision).map { k =>
    val sum = sums(k)
    val cost = Mux(sumAllowed(k) && sum < sumInfinity, sum(23, 0), sumInfinity)
    (RegEnable(cost, sumValid).suggestName(s"candidateClamped$k"), (k + 1).U(4.W))
  }
  var nodes: Seq[(UInt,UInt)] = candidates
  var valid = RegNext(sumValid, false.B)
  var level = 0
  while (nodes.size > 1) {
    val enable = valid
    nodes = nodes.grouped(2).zipWithIndex.map { case (pair,index) =>
      val winner = if (pair.size == 1) pair.head else {
        // Sorted disjoint ranges: every length in the left subtree is smaller
        // than every length in the right subtree, including after reduction.
        val left = pair(0)._1 <= pair(1)._1
        (Mux(left, pair(0)._1, pair(1)._1), Mux(left, pair(0)._2, pair(1)._2))
      }
      (RegEnable(winner._1, enable).suggestName(s"argminLevel${level}_${index}Cost"),
        RegEnable(winner._2, enable).suggestName(s"argminLevel${level}_${index}Length"))
    }.toSeq
    valid = RegNext(enable, false.B).suggestName(s"argminLevel${level}Valid")
    level += 1
  }
  val winner = nodes.head
  io.cost := winner._1; io.length := winner._2
  io.outputValid := rst_n && valid
  }
}

/** Fixed-latency, II=1 DP row evaluation; each semantic level is registered.
  * Packed external buses prevent hierarchy from multiplying public bit ports.
  */
class FastLengthRow(val lanes: Int, val maxPrecision: Int) extends RawModule {
  val clock = IO(Input(Clock())); val rst_n = IO(Input(Bool()))
  require(isPow2(lanes) && lanes >= 2 && lanes <= 64)
  require(maxPrecision >= log2Ceil(lanes) && maxPrecision <= 14)
  private val bankBits = log2Ceil(lanes)
  private val rows = (1 << maxPrecision) / lanes + 1
  private val copies = 1 + math.max(1, maxPrecision - bankBits)
  val io = IO(new Bundle {
    val inputValid = Input(Bool()); val outputValid = Output(Bool())
    val reads = Input(Vec(copies, UInt((lanes * 24).W)))
    val precision = Input(UInt(4.W))
    val minimum = Input(UInt(4.W)); val maximum = Input(UInt(4.W))
    val weighted = Input(Vec(maxPrecision, UInt(24.W))); val infinity = Input(UInt(24.W))
    val target = Input(UInt((maxPrecision + 1).W))
    val row = Input(UInt(log2Ceil(rows).W))
    val costs = Output(UInt((lanes * 24).W))
    val lengths = Output(UInt((lanes * 4).W))
  })
  val reads = io.reads.map(_.asTypeOf(Vec(lanes, UInt(24.W))))
  val precisionOneHot = VecInit((1 to maxPrecision).map(io.precision === _.U)).asUInt
  val allowed = VecInit((1 to maxPrecision).map { bits =>
    bits.U <= io.precision && bits.U >= io.minimum && bits.U <= io.maximum
  }).asUInt
  val rowNonzero = io.row =/= 0.U
  val costs = Wire(Vec(lanes, UInt(24.W)))
  val lengths = Wire(Vec(lanes, UInt(4.W)))
  for (b <- 0 until lanes) {
    val lane = Module(new FastLengthLane(maxPrecision))
    lane.clock := clock; lane.rst_n := rst_n; lane.io.inputValid := io.inputValid
    if (b == 0) io.outputValid := lane.io.outputValid
    lane.io.precisionOneHot := precisionOneHot; lane.io.allowed := allowed
    lane.io.weighted := io.weighted; lane.io.infinity := io.infinity
    lane.io.inRange := ((io.row << bankBits) + b.U) <= io.target
    lane.io.reachable := VecInit((0 until maxPrecision).map { exponent =>
      if (exponent < bankBits) rowNonzero || (b >= (1 << exponent)).B
      else io.row >= (1 << (exponent - bankBits)).U
    }).asUInt
    for (exponent <- 0 until maxPrecision) {
      val delta = 1 << exponent
      val bank = (b - delta + (1 << maxPrecision)) % lanes
      val copy = if (exponent < bankBits) { if (b < delta) 1 else 0 }
                 else 1 + exponent - bankBits
      lane.io.predecessors(exponent) := reads(copy)(bank)
    }
    costs(b) := lane.io.cost; lengths(b) := lane.io.length
  }
  io.costs := costs.asUInt; io.lengths := lengths.asUInt
}
