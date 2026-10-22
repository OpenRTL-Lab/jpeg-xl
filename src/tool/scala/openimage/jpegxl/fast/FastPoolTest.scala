// OpenRTL Lab | JPEG XL | version 1.0.0
// SPDX-License-Identifier: MIT
// SPDX-License-Identifier: MIT
package openimage.jpegxl.fast

import chisel3._
import chisel3.util._
import chisel3.util.experimental.loadMemoryFromFileInline
import _root_.circt.stage.ChiselStage

class ExternalFastPool extends BlackBox {
  override def desiredName = "FastBitPool"
  val io = IO(new Bundle {
    val clock = Input(Clock()); val rst_n = Input(Bool())
    val io = new Bundle {
      val input = Flipped(Decoupled(new FastPackedBlock))
      val output = Decoupled(new FastPoolWord)
    }
  })
}
class FastPoolTest(rows: Int, file: String, negative: Boolean) extends Module {
  val io = IO(new Bundle { val finished = Output(Bool()) })
  val jobs = 20
  def table(path: String, n: Int, width: Int): Mem[UInt] = {
    val m = Mem(n, UInt(width.W)); loadMemoryFromFileInline(m, path); m
  }
  def column(c: Int, w: Int): Mem[UInt] = table(file+s".column-$c.hex", rows, w)
  val context = column(1, 1); val channels = column(2, 3)
  val first = column(3, 1); val last = column(4, 1); val count = column(5, 4)
  val tags = column(6, 32); val bits = column(7, 1024); val lengths = column(8, 36)
  val expected = (0 until 4).map(c => table(file+s".expected-$c.hex", jobs*64, 256))
  val totals = table(file+".totals.hex", jobs, 96); val layouts = table(file+".layouts.hex", jobs, 3)
  val dut = Module(new ExternalFastPool)
  val phase = RegInit(0.U(3.W)); val cycle = RegInit(0.U(32.W)); cycle := cycle+1.U
  val si = RegInit(0.U(log2Ceil(rows+1).W)); val offer = RegInit(false.B)
  val completed = RegInit(0.U(5.W))
  val received = RegInit(VecInit(Seq.fill(jobs)(VecInit(Seq.fill(4)(0.U(24.W))))))
  val seen = RegInit(VecInit(Seq.fill(jobs)(false.B)))
  val sentLast = RegInit(VecInit(Seq.fill(jobs)(false.B)))
  val accepted = RegInit(VecInit(Seq.fill(2)(VecInit(Seq.fill(4)(0.U(24.W))))))
  val active = RegInit(VecInit(Seq.fill(2)(false.B)))
  val twoTail = RegInit(0.U(8.W)); val zeroInput = RegInit(0.U(16.W))
  val both = RegInit(0.U(16.W)); val full = RegInit(0.U(16.W)); val short = RegInit(0.U(16.W))
  val stalled = RegInit(0.U(16.W))
  val running = phase === 2.U || phase === 3.U
  val index = Mux(si < rows.U, si, 0.U)
  val in = dut.io.io.input.bits
  dut.io.clock := clock; dut.io.rst_n := !reset.asBool && phase =/= 1.U
  in.tag := tags(index); in.context := context(index).asBool; in.channels := channels(index)
  in.first := first(index).asBool; in.last := last(index).asBool; in.count := count(index)
  in.bits := bits(index).asTypeOf(in.bits); in.lengths := lengths(index).asTypeOf(in.lengths)
  dut.io.io.input.valid := (phase === 0.U && cycle === 0.U) ||
    (running && si < rows.U && (phase === 2.U || offer))
  dut.io.io.output.ready := running && (phase === 2.U || cycle % 13.U >= 8.U)
  when(phase === 0.U && cycle === 5.U) { phase := 1.U }
  when(phase === 1.U) { assert(!dut.io.io.output.valid, "FAST_POOL_RESET_CANCEL") }
  when(phase === 1.U && cycle === 8.U) { phase := 2.U }
  when(phase === 3.U && si < rows.U && cycle % 5.U =/= 0.U) { offer := true.B }
  val ctx = in.context.asUInt
  val sums = Wire(Vec(4, UInt(10.W)))
  for (c <- 0 until 4) {
    sums(c) := Mux(in.first, 0.U(8.W), accepted(ctx)(c)(7, 0)) +& in.lengths(c)
  }
  val trigger = running && dut.io.io.input.fire && (in.last || sums.map(_ >= 256.U).reduce(_ || _))
  val idealAvailable = ShiftRegister(trigger && phase === 2.U, 2, false.B, true.B)
  when(idealAvailable && phase === 2.U) {
    assert(dut.io.io.output.valid, "FAST_POOL_IDEAL_AVAILABLE_TWO")
  }
  when(running && dut.io.io.input.fire) {
    val job = Wire(UInt(5.W)); job := in.tag - "h58400000".U
    assert(job < jobs.U, "FAST_POOL_INPUT_JOB")
    for (c <- 0 until 4) {
      accepted(ctx)(c) := Mux(in.first, 0.U, accepted(ctx)(c)) + in.lengths(c)
    }
    active(ctx) := !in.last
    when(active(0) && active(1)) { both := both+1.U }
    when(in.last) {
      sentLast(job) := true.B
      when(sums.map(_ > 256.U).reduce(_ || _)) { twoTail := twoTail+1.U }
    }
    when(in.lengths.asUInt === 0.U) { zeroInput := zeroInput+1.U }
    printf("FAST_POOL_INPUT phase=%d index=%d cycle=%d\n", phase, si, cycle)
    si := si+1.U; offer := false.B
  }
  val stall = dut.io.io.output.valid && !dut.io.io.output.ready
  val priorStall = RegNext(stall, false.B)
  val held = RegEnable(dut.io.io.output.bits.asUInt, stall)
  when(running && stall) { stalled := stalled+1.U }
  when(running && priorStall) {
    assert(dut.io.io.output.valid && held === dut.io.io.output.bits.asUInt, "FAST_POOL_OUTPUT_HOLD")
  }
  when(running && dut.io.io.output.fire) {
    val out = dut.io.io.output.bits
    val job = Wire(UInt(5.W)); job := out.tag - "h58400000".U
    assert(job < jobs.U && out.tag === "h58400000".U+job, "FAST_POOL_TAG")
    assert(out.channels === layouts(job) && out.context === job(0), "FAST_POOL_METADATA")
    assert(out.first === !seen(job), "FAST_POOL_FIRST")
    seen(job) := true.B
    val total = totals(job).asTypeOf(Vec(4, UInt(24.W)))
    for (c <- 0 until 4) {
      assert(out.lengths(c) <= 256.U && out.offsets(c) === received(job)(c), "FAST_POOL_OFFSET")
      assert(out.offsets(c)(7, 0) === 0.U, "FAST_POOL_WORD_ALIGNMENT")
      val word = Wire(UInt(log2Ceil(jobs*64).W)); word := (job << 6) + (out.offsets(c) >> 8)
      val mask = ((1.U(257.W) << out.lengths(c))-1.U)(255, 0)
      val golden = expected(c)(word) & mask
      assert(out.bits(c) === (if (negative && c == 0) golden ^ 1.U else golden), "FAST_POOL_OFFICIAL_BITS")
      assert(out.totalBits(c) <= total(c) && out.totalBits(c) >= out.offsets(c)+out.lengths(c),
        "FAST_POOL_TOTAL_PROGRESS")
      when(!out.last) {
        assert(out.lengths(c) === 0.U || out.lengths(c) === 256.U, "FAST_POOL_NO_EARLY_SHORT")
      }
      received(job)(c) := received(job)(c)+out.lengths(c)
      when(out.last) {
        assert(out.totalBits(c) === total(c) && received(job)(c)+out.lengths(c) === total(c),
          "FAST_POOL_EXACT_FINAL_TOTAL")
      }
    }
    when(out.lengths.map(_ === 256.U).reduce(_ || _)) { full := full+1.U }
    when(out.last && out.lengths.map(x => x > 0.U && x < 256.U).reduce(_ || _)) { short := short+1.U }
    printf("FAST_POOL_WORD phase=%d job=%d first=%d last=%d bits=%x lengths=%x offsets=%x totals=%x\n",
      phase, job, out.first, out.last, out.bits.asUInt, out.lengths.asUInt, out.offsets.asUInt, out.totalBits.asUInt)
    when(out.last) {
      assert(sentLast(job), "FAST_POOL_LAST_ACCEPTED")
      completed := completed+1.U
      when(completed === (jobs-1).U) {
        assert(si === rows.U && twoTail === 8.U && zeroInput >= 20.U && both > 0.U && full > 0.U && short > 0.U,
          "FAST_POOL_COVERAGE")
        when(phase === 3.U) { assert(stalled > 0.U, "FAST_POOL_STALL_COVERAGE") }
        printf("FAST_POOL_PHASE_PASS phase=%d jobs=%d inputs=%d two_tail=%d\n", phase, completed+1.U, si, twoTail)
        when(phase === 3.U) { printf("FAST_POOL_SUITE_PASS\n") }
        phase := phase+1.U; si := 0.U; completed := 0.U; offer := false.B
        received.foreach(_.foreach(_ := 0.U)); seen.foreach(_ := false.B); sentLast.foreach(_ := false.B)
        accepted.foreach(_.foreach(_ := 0.U)); active.foreach(_ := false.B)
        twoTail := 0.U; zeroInput := 0.U; both := 0.U; full := 0.U; short := 0.U; stalled := 0.U
      }
    }
  }
  assert(cycle < 200000.U, "FAST_POOL_WATCHDOG")
  io.finished := phase === 4.U
}
object EmitFastPool {
  def main(args: Array[String]): Unit = {
    require(args.length == 1)
    ChiselStage.emitSystemVerilogFile(new FastBitPool, Array("--target-dir", args(0)),
      Array("--split-verilog", "--disable-all-randomization", "--strip-debug-info"))
  }
}
object EmitFastPoolTest {
  def main(args: Array[String]): Unit = {
    require(args.length == 3)
    val f = scala.io.Source.fromFile(args(1))
    val n = try f.getLines().count(_.nonEmpty) finally f.close()
    ChiselStage.emitSystemVerilogFile(new FastPoolTest(n, args(1), args(2) == "negative"),
      Array("--target-dir", args(0)),
      Array("--split-verilog", "--disable-all-randomization", "--strip-debug-info"))
  }
}
