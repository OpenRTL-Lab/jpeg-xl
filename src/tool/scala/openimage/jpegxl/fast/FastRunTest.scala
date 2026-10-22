// OpenRTL Lab | JPEG XL | version 1.0.0
// SPDX-License-Identifier: MIT
// SPDX-License-Identifier: MIT
package openimage.jpegxl.fast

import chisel3._
import chisel3.util._
import chisel3.util.experimental.loadMemoryFromFileInline
import _root_.circt.stage.ChiselStage

class ExternalFastRun extends BlackBox {
  override def desiredName = "FastRunPlanner"
  val io = IO(new Bundle {
    val clock = Input(Clock())
    val rst_n = Input(Bool())
    val io = new Bundle {
      val input = Flipped(Decoupled(new FastRunInput))
      val output = Decoupled(new FastRunOutput)
    }
  })
}

class FastRunTest(rows: Seq[Array[String]], negative: Boolean, vectorPath: String) extends Module {
  val io = IO(new Bundle { val finished = Output(Bool()) })
  require(rows.nonEmpty)
  val iw = log2Ceil(rows.size + 1)
  def table(column: Int, bits: Int, radix: Int = 10): Mem[UInt] = {
    val rom = Mem(rows.size, UInt(bits.W))
    loadMemoryFromFileInline(rom, vectorPath + ".column-" + column + ".hex")
    rom
  }
  val contexts = table(1, 1)
  val channels = table(2, 3)
  val first = table(3, 1)
  val last = table(4, 1)
  val sampling = table(5, 1)
  val counts = table(6, 4)
  val tags = table(7, 32)
  val repeats = table(8, 14)
  val residuals = table(9, 512, 16)
  val prefixes = table(10, 16, 16)
  val emits = table(11, 4, 16)
  val runs = table(12, 68, 16)
  val skips = table(13, 16, 16)
  val dut = Module(new ExternalFastRun)
  val cycle = RegInit(0.U(32.W)); cycle := cycle + 1.U
  val phase = RegInit(0.U(3.W))
  val si = RegInit(0.U(iw.W)); val ri = RegInit(0.U(iw.W))
  val sr = RegInit(0.U(14.W)); val rr = RegInit(0.U(14.W))
  val sent = RegInit(0.U(32.W)); val received = RegInit(0.U(32.W))
  val firstCycle = Reg(UInt(32.W)); val lastCycle = Reg(UInt(32.W))
  val offer = RegInit(false.B)
  val running = phase === 2.U || phase === 3.U
  val index = Mux(phase === 0.U, 0.U, Mux(si < rows.size.U, si, 0.U))
  dut.io.clock := clock
  dut.io.rst_n := !reset.asBool && phase =/= 1.U
  dut.io.io.input.valid := phase === 0.U && cycle === 0.U ||
    running && si < rows.size.U && (phase === 2.U || offer)
  val in = dut.io.io.input.bits
  in := 0.U.asTypeOf(in)
  in.context := contexts(index).asBool
  in.first := first(index).asBool
  in.last := last(index).asBool
  in.sampling := sampling(index).asBool
  in.block.tag := tags(index)
  in.block.channels := channels(index)
  in.block.count := counts(index)
  in.block.residual := residuals(index).asTypeOf(in.block.residual)
  for (c <- 0 until 4) in.block.zeroPrefix(c) := prefixes(index)(4 * c + 3, 4 * c)
  dut.io.io.output.ready := running && (phase === 2.U || cycle % 7.U >= 3.U)
  when(phase === 0.U && cycle === 5.U) { phase := 1.U }
  when(phase === 1.U && cycle === 8.U) { phase := 2.U }
  when(phase === 1.U) { assert(!dut.io.io.output.valid, "FAST_RUN_RESET_CANCEL") }
  when(phase === 3.U && !offer && si < rows.size.U && cycle % 5.U =/= 2.U) { offer := true.B }
  when(running && dut.io.io.input.fire) {
    when(sent === 0.U) { firstCycle := cycle }
    sent := sent + 1.U
    offer := false.B
    when(sr + 1.U === repeats(index)) { si := si + 1.U; sr := 0.U }
      .otherwise { sr := sr + 1.U }
  }
  val stall = dut.io.io.output.valid && !dut.io.io.output.ready
  val priorStall = RegNext(stall, false.B)
  val prior = RegEnable(dut.io.io.output.bits.asUInt, stall)
  when(priorStall && running) {
    assert(dut.io.io.output.valid && dut.io.io.output.bits.asUInt === prior, "FAST_RUN_OUTPUT_HOLD")
  }
  when(running && dut.io.io.output.fire) {
    val out = dut.io.io.output.bits
    assert(ri < rows.size.U, "FAST_RUN_NO_EXTRA")
    assert(out.input.context === contexts(ri).asBool &&
      out.input.first === first(ri).asBool && out.input.last === last(ri).asBool &&
      out.input.sampling === sampling(ri).asBool, "FAST_RUN_CONTROL")
    assert(out.input.block.tag === tags(ri) && out.input.block.count === counts(ri) &&
      out.input.block.channels === channels(ri), "FAST_RUN_METADATA")
    assert(out.input.block.residual.asUInt === residuals(ri), "FAST_RUN_RESIDUAL_PASSTHROUGH")
    for (c <- 0 until 4) {
      assert(out.emit(c) === emits(ri)(c), "FAST_RUN_OFFICIAL_EMIT")
      val expected = runs(ri)(17 * c + 16, 17 * c)
      assert(out.run(c) === (if (negative) expected ^ 1.U else expected), "FAST_RUN_OFFICIAL_RUN")
      assert(out.skip(c) === skips(ri)(4 * c + 3, 4 * c), "FAST_RUN_OFFICIAL_SKIP")
    }
    when(received === 0.U && phase === 2.U) {
      assert(cycle - firstCycle === 1.U, "FAST_RUN_IDEAL_LATENCY")
    }.elsewhen(phase === 2.U) {
      assert(cycle - lastCycle === 1.U, "FAST_RUN_IDEAL_II")
    }
    lastCycle := cycle
    received := received + 1.U
    when(rr + 1.U === repeats(ri)) {
      printf("FAST_RUN_RECORD phase=%d index=%d repeat=%d emit=%x runs=%x skips=%x\n",
        phase, ri, repeats(ri), out.emit.asUInt, out.run.asUInt, out.skip.asUInt)
      rr := 0.U; ri := ri + 1.U
      when(ri === (rows.size - 1).U) {
        assert(si === rows.size.U, "FAST_RUN_ALL_INPUTS")
        printf("FAST_RUN_PHASE_PASS phase=%d blocks=%d elapsed=%d\n",
          phase, received + 1.U, cycle - firstCycle + 1.U)
        when(phase === 3.U) { printf("FAST_RUN_SUITE_PASS\n") }
        phase := phase + 1.U
        si := 0.U; ri := 0.U; sr := 0.U; rr := 0.U
        sent := 0.U; received := 0.U; offer := false.B
      }
    }.otherwise { rr := rr + 1.U }
  }
  assert(cycle < 200000.U, "FAST_RUN_WATCHDOG")
  io.finished := phase === 4.U
}

object EmitFastRun {
  def main(args: Array[String]): Unit = {
    require(args.length == 1)
    ChiselStage.emitSystemVerilogFile(new FastRunPlanner, Array("--target-dir", args(0)),
      Array("--split-verilog", "--disable-all-randomization", "--strip-debug-info"))
  }
}

object EmitFastRunTest {
  def main(args: Array[String]): Unit = {
    require(args.length == 3)
    val source = scala.io.Source.fromFile(args(1))
    val rows = try source.getLines().filter(_.nonEmpty).map(_.split("\t")).toVector finally source.close()
    ChiselStage.emitSystemVerilogFile(new FastRunTest(rows, args(2) == "negative", args(1)),
      Array("--target-dir", args(0)),
      Array("--split-verilog", "--disable-all-randomization", "--strip-debug-info"))
  }
}
