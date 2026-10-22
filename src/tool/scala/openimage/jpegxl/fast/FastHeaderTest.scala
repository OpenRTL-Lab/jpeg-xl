// OpenRTL Lab | JPEG XL | version 1.0.0
// SPDX-License-Identifier: MIT
// SPDX-License-Identifier: MIT
package openimage.jpegxl.fast

import chisel3._
import chisel3.util._
import chisel3.util.experimental.loadMemoryFromFileInline
import _root_.circt.stage.ChiselStage

class ExternalFastHeader extends BlackBox {
  override def desiredName = "FastHeaderGroup"
  val io = IO(new Bundle {
    val clock = Input(Clock()); val rst_n = Input(Bool())
    val io = new Bundle {
      val input = Flipped(Decoupled(new FastPrefixGroupResult))
      val output = Decoupled(new FastHeaderGroupResult)
    }
  })
}
class FastHeaderTest(rows: Int, file: String, negative: Boolean) extends Module {
  require(rows > 0 && rows % 4 == 0)
  private val groups = rows / 4
  val io = IO(new Bundle { val finished = Output(Bool()) })
  def table(col: Int, bits: Int): Mem[UInt] = {
    val m = Mem(rows, UInt(bits.W)); loadMemoryFromFileInline(m, file + s".column-$col.hex"); m
  }
  val layouts = table(1, 3); val rawLengths = table(2, 76); val lzLengths = table(3, 132)
  val bits = table(4, 512); val lengths = table(5, 10)
  val dut = Module(new ExternalFastHeader)
  val phase = RegInit(0.U(3.W)); val cycle = RegInit(0.U(32.W)); cycle := cycle + 1.U
  val si = RegInit(0.U(log2Ceil(groups + 1).W)); val ri = RegInit(0.U(log2Ceil(groups + 1).W))
  val offer = RegInit(false.B); val reported = RegInit(false.B)
  val started = Reg(UInt(32.W)); val available = Reg(UInt(32.W))
  val idealLatency = Reg(Vec(groups, UInt(32.W)))
  val running = phase === 2.U || phase === 3.U
  val index = Mux(si < groups.U, si, 0.U)
  dut.io.clock := clock; dut.io.rst_n := !reset.asBool && phase =/= 1.U
  dut.io.io.input.bits := 0.U.asTypeOf(dut.io.io.input.bits)
  dut.io.io.input.bits.tag := index; dut.io.io.input.bits.channels := layouts(index << 2)
  for (c <- 0 until 4) {
    val address = (index << 2) + c.U
    val code = dut.io.io.input.bits.tables(c)
    code.tag := index
    code.rawLengths := rawLengths(address).asTypeOf(code.rawLengths)
    code.lzLengths := lzLengths(address).asTypeOf(code.lzLengths)
  }
  dut.io.io.input.valid := phase === 0.U && cycle === 0.U ||
    running && si < groups.U && (phase === 2.U || offer)
  dut.io.io.output.ready := running && (phase === 2.U || cycle % 31.U >= 23.U)
  when(phase === 0.U && cycle === 5.U) { phase := 1.U }
  when(phase === 1.U) { assert(!dut.io.io.output.valid, "FAST_HEADER_RESET_CANCEL") }
  when(phase === 1.U && cycle === 8.U) { phase := 2.U }
  when(phase === 3.U && si < groups.U && cycle % 7.U =/= 0.U) { offer := true.B }
  when(running && dut.io.io.input.fire) {
    si := si + 1.U; started := cycle; offer := false.B; reported := false.B
  }
  when(running && dut.io.io.output.valid && !reported) {
    reported := true.B; available := cycle
    when(phase === 2.U) { idealLatency(ri) := cycle - started }
      .otherwise { assert(cycle - started === idealLatency(ri), "FAST_HEADER_AVAILABLE_LATENCY") }
  }
  val stalled = dut.io.io.output.valid && !dut.io.io.output.ready
  val priorStall = RegNext(stalled, false.B)
  val saved = RegEnable(dut.io.io.output.bits.asUInt, stalled)
  when(running && priorStall) {
    assert(dut.io.io.output.valid && dut.io.io.output.bits.asUInt === saved, "FAST_HEADER_OUTPUT_HOLD")
  }
  when(running && dut.io.io.output.fire) {
    val out = dut.io.io.output.bits
    assert(ri < groups.U && out.tag === ri && out.channels === layouts(ri << 2), "FAST_HEADER_METADATA")
    for (c <- 0 until 4) {
      val address = (ri << 2) + c.U
      assert(out.headers(c).tag === ri, "FAST_HEADER_CHANNEL_TAG")
      assert(out.headers(c).length === lengths(address), "FAST_HEADER_OFFICIAL_LENGTH")
      assert(out.headers(c).bits === (if (negative && c == 0) bits(address) ^ 1.U else bits(address)),
        "FAST_HEADER_OFFICIAL_BITS")
      printf("FAST_HEADER_RESULT phase=%d index=%d bits=%x length=%d\n",
        phase, address, out.headers(c).bits, out.headers(c).length)
    }
    printf("FAST_HEADER_GROUP phase=%d index=%d start=%d available=%d done=%d\n",
      phase, ri, started, Mux(reported, available, cycle), cycle)
    ri := ri + 1.U
    when(ri === (groups - 1).U) {
      assert(si === groups.U, "FAST_HEADER_ALL_COMMANDS")
      when(phase === 3.U) { printf("FAST_HEADER_SUITE_PASS\n") }
      phase := phase + 1.U; si := 0.U; ri := 0.U; offer := false.B; reported := false.B
    }
  }
  assert(cycle < 200000.U, "FAST_HEADER_WATCHDOG")
  io.finished := phase === 4.U
}
object EmitFastHeader {
  def main(args: Array[String]): Unit = {
    require(args.length == 1)
    ChiselStage.emitSystemVerilogFile(new FastHeaderGroup, Array("--target-dir", args(0)),
      Array("--split-verilog", "--disable-all-randomization", "--strip-debug-info"))
  }
}
object EmitFastHeaderTest {
  def main(args: Array[String]): Unit = {
    require(args.length == 3)
    val f = scala.io.Source.fromFile(args(1))
    val n = try f.getLines().count(_.nonEmpty) finally f.close()
    ChiselStage.emitSystemVerilogFile(new FastHeaderTest(n, args(1), args(2) == "negative"),
      Array("--target-dir", args(0)),
      Array("--split-verilog", "--disable-all-randomization", "--strip-debug-info"))
  }
}
