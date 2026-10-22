// OpenRTL Lab | JPEG XL | version 1.0.0
// SPDX-License-Identifier: MIT
// SPDX-License-Identifier: MIT
package openimage.jpegxl.fast

import chisel3._
import chisel3.util._
import chisel3.util.experimental.loadMemoryFromFileInline
import _root_.circt.stage.ChiselStage

class ExternalFastLength extends BlackBox {
  override def desiredName = "FastLengthSolver"
  val io = IO(new Bundle {
    val clock = Input(Clock()); val rst_n = Input(Bool())
    val io = new Bundle {
      val command = Flipped(Decoupled(new FastLengthCommand))
      val output = Decoupled(new FastLengthResult)
    }
  })
}
class FastLengthTest(rows: Int, file: String, negative: Boolean) extends Module {
  val io = IO(new Bundle { val finished = Output(Bool()) })
  def table(col: Int, bits: Int): Mem[UInt] = {
    val m = Mem(rows, UInt(bits.W)); loadMemoryFromFileInline(m, file + s".column-$col.hex"); m
  }
  val frequencies = table(1, 594); val minimum = table(2, 132)
  val maximum = table(3, 132); val expected = table(4, 132); val cost = table(5, 24)
  val dut = Module(new ExternalFastLength)
  val phase = RegInit(0.U(3.W)); val cycle = RegInit(0.U(32.W)); cycle := cycle + 1.U
  val si = RegInit(0.U(log2Ceil(rows + 1).W)); val ri = RegInit(0.U(log2Ceil(rows + 1).W))
  val started = Reg(UInt(32.W)); val offer = RegInit(false.B)
  val running = phase === 2.U || phase === 3.U
  val index = Mux(si < rows.U, si, 0.U)
  dut.io.clock := clock; dut.io.rst_n := !reset.asBool && phase =/= 1.U
  dut.io.io.command.bits.tag := index
  dut.io.io.command.bits.frequencies := frequencies(index).asTypeOf(dut.io.io.command.bits.frequencies)
  dut.io.io.command.bits.minimum := minimum(index).asTypeOf(dut.io.io.command.bits.minimum)
  dut.io.io.command.bits.maximum := maximum(index).asTypeOf(dut.io.io.command.bits.maximum)
  dut.io.io.command.valid := phase === 0.U && cycle === 0.U ||
    running && si < rows.U && (phase === 2.U || offer)
  dut.io.io.output.ready := running && (phase === 2.U || cycle % 29.U >= 19.U)
  when(phase === 0.U && cycle === 5.U) { phase := 1.U }
  when(phase === 1.U) { assert(!dut.io.io.output.valid, "FAST_LENGTH_RESET_CANCEL") }
  when(phase === 1.U && cycle === 8.U) { phase := 2.U }
  when(phase === 3.U && si < rows.U && cycle % 5.U =/= 0.U) { offer := true.B }
  when(running && dut.io.io.command.fire) { si := si + 1.U; started := cycle; offer := false.B }
  val stall = dut.io.io.output.valid && !dut.io.io.output.ready
  val priorStall = RegNext(stall, false.B)
  val saved = RegEnable(dut.io.io.output.bits.asUInt, stall)
  when(running && priorStall) {
    assert(dut.io.io.output.valid && saved === dut.io.io.output.bits.asUInt, "FAST_LENGTH_OUTPUT_HOLD")
  }
  when(running && dut.io.io.output.fire) {
    val out = dut.io.io.output.bits
    assert(ri < rows.U && out.tag === ri, "FAST_LENGTH_TAG")
    assert(out.lengths.asUInt === (if (negative) expected(ri) ^ 1.U else expected(ri)),
      "FAST_LENGTH_OFFICIAL_LENGTHS")
    assert(out.cost === cost(ri), "FAST_LENGTH_OFFICIAL_COST")
    when(phase === 2.U) {
      assert(out.cycles + 1.U === cycle - started, "FAST_LENGTH_RECORDED_CYCLES")
    }
    printf("FAST_LENGTH_RESULT phase=%d index=%d lengths=%x cost=%d cycles=%d\n",
      phase, ri, out.lengths.asUInt, out.cost, out.cycles)
    ri := ri + 1.U
    when(ri === (rows - 1).U) {
      assert(si === rows.U, "FAST_LENGTH_ALL_COMMANDS")
      when(phase === 3.U) { printf("FAST_LENGTH_SUITE_PASS\n") }
      phase := phase + 1.U; si := 0.U; ri := 0.U; offer := false.B
    }
  }
  assert(cycle < 2000000.U, "FAST_LENGTH_WATCHDOG")
  io.finished := phase === 4.U
}
object EmitFastLength {
  def main(args: Array[String]): Unit = {
    require(args.length == 1)
    ChiselStage.emitSystemVerilogFile(new FastLengthSolver, Array("--target-dir", args(0)),
      Array("--split-verilog", "--disable-all-randomization", "--strip-debug-info"))
  }
}
object EmitFastLengthTest {
  def main(args: Array[String]): Unit = {
    require(args.length == 3)
    val f = scala.io.Source.fromFile(args(1))
    val n = try f.getLines().count(_.nonEmpty) finally f.close()
    ChiselStage.emitSystemVerilogFile(new FastLengthTest(n, args(1), args(2) == "negative"),
      Array("--target-dir", args(0)),
      Array("--split-verilog", "--disable-all-randomization", "--strip-debug-info"))
  }
}
