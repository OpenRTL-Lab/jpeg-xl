// OpenRTL Lab | JPEG XL | version 1.0.0
// SPDX-License-Identifier: MIT
// SPDX-License-Identifier: MIT
package openimage.jpegxl.fast

import chisel3._
import chisel3.util._
import chisel3.util.experimental.loadMemoryFromFileInline
import _root_.circt.stage.ChiselStage

class ExternalFastStatistics extends BlackBox {
  override def desiredName = "FastStatistics"
  val io = IO(new Bundle {
    val clock = Input(Clock())
    val rst_n = Input(Bool())
    val io = new Bundle {
      val command = Flipped(Decoupled(new FastStatisticsCommand))
      val input = Flipped(Decoupled(new FastRunOutput))
      val output = Decoupled(new FastStatisticsResult)
    }
  })
}

class FastStatisticsTest(rows: Int, results: Int, vectors: String, negative: Boolean) extends Module {
  val io = IO(new Bundle { val finished = Output(Bool()) })
  def table(file: String, n: Int, bits: Int): Mem[UInt] = {
    val m = Mem(n, UInt(bits.W)); loadMemoryFromFileInline(m, file); m
  }
  def input(col: Int, bits: Int) = table(vectors + s".column-$col.hex", rows, bits)
  def answer(col: Int, bits: Int) = table(vectors + s".answers.column-$col.hex", results, bits)
  val actions = input(1, 2); val contexts = input(2, 1); val channels = input(3, 3)
  val tags = input(4, 32); val first = input(5, 1); val last = input(6, 1)
  val counts = input(7, 4); val tokens = input(8, 160); val emits = input(9, 4)
  val runs = input(10, 68); val skips = input(11, 16)
  val outContexts = answer(1, 1); val outChannels = answer(2, 3)
  val outTags = answer(3, 32); val outSamples = answer(4, 10)
  val outRaw = answer(5, 760); val outLz = answer(6, 1320)
  val rawBase = Seq(3843, 852, 1270, 1214, 1014, 727, 481, 300, 159, 51,
    5, 1, 1, 1, 1, 1, 1, 1, 1)
  val lzBase = Seq(29, 27, 25, 23, 21, 21, 19, 18, 21, 17, 16, 15, 15, 14,
    13, 13, 137, 98, 61, 34, 1, 1, 1, 1, 1, 1, 1, 1, 0, 0, 0, 0, 0)
  val dut = Module(new ExternalFastStatistics)
  val cycle = RegInit(0.U(32.W)); cycle := cycle + 1.U
  val phase = RegInit(0.U(3.W))
  val si = RegInit(0.U(log2Ceil(rows + 1).W))
  val ri = RegInit(0.U(log2Ceil(results + 1).W))
  val offer = RegInit(false.B)
  val running = phase === 2.U || phase === 3.U
  val index = Mux(si < rows.U, si, 0.U)
  val command = actions(index) =/= 2.U
  dut.io.clock := clock
  dut.io.rst_n := !reset.asBool && phase =/= 1.U
  dut.io.io.command.bits.context := contexts(index).asBool
  dut.io.io.command.bits.channels := channels(index)
  dut.io.io.command.bits.tag := tags(index)
  dut.io.io.command.bits.empty := actions(index) === 0.U
  val valid = running && si < rows.U && (phase === 2.U || offer)
  // First command is empty: hold its response, then cancel it by active reset.
  dut.io.io.command.valid := valid && command || phase === 0.U && cycle === 0.U
  dut.io.io.input.valid := valid && !command
  val in = dut.io.io.input.bits
  in := 0.U.asTypeOf(in)
  in.input.context := contexts(index).asBool
  in.input.first := first(index).asBool; in.input.last := last(index).asBool
  in.input.sampling := true.B
  in.input.block.tag := tags(index); in.input.block.channels := channels(index)
  in.input.block.count := counts(index)
  in.input.block.token := tokens(index).asTypeOf(in.input.block.token)
  in.emit := emits(index).asTypeOf(in.emit)
  in.run := runs(index).asTypeOf(in.run)
  in.skip := skips(index).asTypeOf(in.skip)
  dut.io.io.output.ready := running && (phase === 2.U || cycle % 19.U >= 12.U)
  when(phase === 0.U && cycle === 5.U) { phase := 1.U }
  when(phase === 1.U) { assert(!dut.io.io.output.valid, "FAST_STATS_RESET_CANCEL") }
  when(phase === 1.U && cycle === 8.U) { phase := 2.U }
  when(phase === 3.U && !offer && si < rows.U && cycle % 5.U =/= 2.U) { offer := true.B }
  val consumed = dut.io.io.command.fire || dut.io.io.input.fire
  val priorOutputDue = RegNext(consumed &&
    (command && actions(index) === 0.U || !command && last(index).asBool), false.B)
  when(running && consumed) { si := si + 1.U; offer := false.B }
  val stalled = dut.io.io.output.valid && !dut.io.io.output.ready
  val priorStall = RegNext(stalled, false.B)
  val saved = RegEnable(dut.io.io.output.bits.asUInt, stalled)
  when(priorStall && running) {
    assert(dut.io.io.output.valid && dut.io.io.output.bits.asUInt === saved, "FAST_STATS_OUTPUT_HOLD")
  }
  when(phase === 2.U && priorOutputDue) {
    assert(dut.io.io.output.valid, "FAST_STATS_FINAL_LATENCY_ONE")
  }
  // Context-ready valid input is consumed every ideal cycle, including bin collisions.
  when(phase === 2.U && dut.io.io.input.valid) {
    assert(dut.io.io.input.ready, "FAST_STATS_IDEAL_BLOCK_II_ONE")
  }
  when(running && dut.io.io.output.fire) {
    val out = dut.io.io.output.bits
    assert(ri < results.U, "FAST_STATS_NO_EXTRA")
    assert(out.context === outContexts(ri).asBool && out.tag === outTags(ri) &&
      out.channels === outChannels(ri) && out.samples === outSamples(ri), "FAST_STATS_METADATA")
    for (c <- 0 until 4) {
      for (s <- 0 until 19) {
        val expected = (outRaw(ri)(10 * (19 * c + s) + 9, 10 * (19 * c + s)) << 8) +
          Mux(s.U < Mux(outChannels(ri) >= 3.U, 11.U, 10.U), rawBase(s).U, 0.U)
        assert(out.raw(c)(s) === (if (negative) expected ^ 1.U else expected), "FAST_STATS_OFFICIAL_RAW")
      }
      for (s <- 0 until 33) {
        val expected = (outLz(ri)(10 * (33 * c + s) + 9, 10 * (33 * c + s)) << 8) + lzBase(s).U
        assert(out.lz(c)(s) === expected, "FAST_STATS_OFFICIAL_LZ")
      }
    }
    printf("FAST_STATS_RESULT phase=%d index=%d raw=%x lz=%x\n", phase, ri, out.raw.asUInt, out.lz.asUInt)
    ri := ri + 1.U
    when(ri === (results - 1).U) {
      assert(si === rows.U, "FAST_STATS_ALL_INPUTS")
      printf("FAST_STATS_PHASE_PASS phase=%d results=%d\n", phase, ri + 1.U)
      when(phase === 3.U) { printf("FAST_STATS_SUITE_PASS\n") }
      si := 0.U; ri := 0.U; offer := false.B; phase := phase + 1.U
    }
  }
  assert(cycle < 100000.U, "FAST_STATS_WATCHDOG")
  io.finished := phase === 4.U
}

object EmitFastStatistics {
  def main(args: Array[String]): Unit = {
    require(args.length == 1)
    ChiselStage.emitSystemVerilogFile(new FastStatistics, Array("--target-dir", args(0)),
      Array("--split-verilog", "--disable-all-randomization", "--strip-debug-info"))
  }
}
object EmitFastStatisticsTest {
  def main(args: Array[String]): Unit = {
    require(args.length == 3)
    def lines(file: String): Int = {
      val f = scala.io.Source.fromFile(file)
      try f.getLines().count(_.nonEmpty) finally f.close()
    }
    ChiselStage.emitSystemVerilogFile(new FastStatisticsTest(lines(args(1)),
      lines(args(1) + ".answers"), args(1), args(2) == "negative"),
      Array("--target-dir", args(0)),
      Array("--split-verilog", "--disable-all-randomization", "--strip-debug-info"))
  }
}
