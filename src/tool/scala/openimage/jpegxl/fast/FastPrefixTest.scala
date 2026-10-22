// OpenRTL Lab | JPEG XL | version 1.0.0
// SPDX-License-Identifier: MIT
// SPDX-License-Identifier: MIT
package openimage.jpegxl.fast

import chisel3._
import chisel3.util._
import chisel3.util.experimental.loadMemoryFromFileInline
import _root_.circt.stage.ChiselStage

class ExternalFastPrefix extends BlackBox {
  override def desiredName = "FastPrefixGroup"
  val io = IO(new Bundle {
    val clock = Input(Clock()); val rst_n = Input(Bool())
    val io = new Bundle {
      val input = Flipped(Decoupled(new FastStatisticsResult))
      val output = Decoupled(new FastPrefixGroupResult)
    }
  })
}
class FastPrefixTest(rows: Int, file: String, negative: Boolean) extends Module {
  require(rows > 0 && rows % 4 == 0)
  private val groups = rows / 4
  val io = IO(new Bundle { val finished = Output(Bool()) })
  def table(col: Int, bits: Int): Mem[UInt] = {
    val m = Mem(rows, UInt(bits.W))
    loadMemoryFromFileInline(m, file + s".column-$col.hex"); m
  }
  val layouts = table(1, 3)
  val raw = table(2, 342); val lz = table(3, 594)
  val rawLengths = table(4, 76); val rawBits = table(5, 152)
  val lzLengths = table(6, 132); val lzBits = table(7, 528)
  val dut = Module(new ExternalFastPrefix)
  val phase = RegInit(0.U(3.W)); val cycle = RegInit(0.U(32.W)); cycle := cycle + 1.U
  val si = RegInit(0.U(log2Ceil(groups + 1).W))
  val ri = RegInit(0.U(log2Ceil(groups + 1).W))
  val started = Reg(UInt(32.W)); val available = Reg(UInt(32.W))
  val reported = RegInit(false.B); val offer = RegInit(false.B)
  val idealLatency = Reg(Vec(groups, UInt(32.W)))
  val running = phase === 2.U || phase === 3.U
  val index = Mux(si < groups.U, si, 0.U)
  dut.io.clock := clock; dut.io.rst_n := !reset.asBool && phase =/= 1.U
  dut.io.io.input.bits := 0.U.asTypeOf(dut.io.io.input.bits)
  dut.io.io.input.bits.tag := index
  dut.io.io.input.bits.channels := layouts(index << 2)
  for (c <- 0 until 4) {
    val address = (index << 2) + c.U
    dut.io.io.input.bits.raw(c) := raw(address).asTypeOf(dut.io.io.input.bits.raw(c))
    dut.io.io.input.bits.lz(c) := lz(address).asTypeOf(dut.io.io.input.bits.lz(c))
  }
  dut.io.io.input.valid := phase === 0.U && cycle === 0.U ||
    running && si < groups.U && (phase === 2.U || offer)
  dut.io.io.output.ready := running && (phase === 2.U || cycle % 31.U >= 23.U)
  when(phase === 0.U && cycle === 5.U) { phase := 1.U }
  when(phase === 1.U) { assert(!dut.io.io.output.valid, "FAST_PREFIX_RESET_CANCEL") }
  when(phase === 1.U && cycle === 8.U) { phase := 2.U }
  when(phase === 3.U && si < groups.U && cycle % 7.U =/= 0.U) { offer := true.B }
  when(running && dut.io.io.input.fire) {
    si := si + 1.U; started := cycle; offer := false.B; reported := false.B
  }
  val measured = cycle - started
  when(running && dut.io.io.output.valid && !reported) {
    reported := true.B; available := cycle
    when(phase === 2.U) { idealLatency(ri) := measured }
      .otherwise { assert(measured === idealLatency(ri), "FAST_PREFIX_AVAILABLE_LATENCY") }
  }
  val stall = dut.io.io.output.valid && !dut.io.io.output.ready
  val priorStall = RegNext(stall, false.B)
  val saved = RegEnable(dut.io.io.output.bits.asUInt, stall)
  when(running && priorStall) {
    assert(dut.io.io.output.valid && saved === dut.io.io.output.bits.asUInt, "FAST_PREFIX_OUTPUT_HOLD")
  }
  when(running && dut.io.io.output.fire) {
    val out = dut.io.io.output.bits
    assert(ri < groups.U && out.tag === ri, "FAST_PREFIX_GROUP_TAG")
    assert(out.channels === layouts(ri << 2), "FAST_PREFIX_LAYOUT")
    for (c <- 0 until 4) {
      val address = (ri << 2) + c.U
      val code = out.tables(c)
      assert(code.tag === ri, "FAST_PREFIX_CHANNEL_TAG")
      assert(code.rawLengths.asUInt === rawLengths(address), "FAST_PREFIX_OFFICIAL_RAW_LENGTHS")
      assert(code.rawBits.asUInt === (if (negative && c == 0) rawBits(address) ^ 1.U else rawBits(address)),
        "FAST_PREFIX_OFFICIAL_RAW_BITS")
      assert(code.lzLengths.asUInt === lzLengths(address), "FAST_PREFIX_OFFICIAL_LZ_LENGTHS")
      assert(code.lzBits.asUInt === lzBits(address), "FAST_PREFIX_OFFICIAL_LZ_BITS")
      printf("FAST_PREFIX_TABLE phase=%d index=%d raw_lengths=%x raw_bits=%x lz_lengths=%x lz_bits=%x\n",
        phase, address, code.rawLengths.asUInt, code.rawBits.asUInt, code.lzLengths.asUInt, code.lzBits.asUInt)
    }
    printf("FAST_PREFIX_GROUP phase=%d index=%d start=%d available=%d done=%d\n",
      phase, ri, started, Mux(reported, available, cycle), cycle)
    ri := ri + 1.U
    when(ri === (groups - 1).U) {
      assert(si === groups.U, "FAST_PREFIX_ALL_COMMANDS")
      when(phase === 3.U) { printf("FAST_PREFIX_SUITE_PASS\n") }
      phase := phase + 1.U; si := 0.U; ri := 0.U; offer := false.B; reported := false.B
    }
  }
  assert(cycle < 2000000.U, "FAST_PREFIX_WATCHDOG")
  io.finished := phase === 4.U
}
object EmitFastPrefix {
  def main(args: Array[String]): Unit = {
    require(args.length == 1)
    ChiselStage.emitSystemVerilogFile(new FastPrefixGroup, Array("--target-dir", args(0)),
      Array("--split-verilog", "--disable-all-randomization", "--strip-debug-info"))
  }
}
object EmitFastPrefixTest {
  def main(args: Array[String]): Unit = {
    require(args.length == 3)
    val f = scala.io.Source.fromFile(args(1))
    val n = try f.getLines().count(_.nonEmpty) finally f.close()
    ChiselStage.emitSystemVerilogFile(new FastPrefixTest(n, args(1), args(2) == "negative"),
      Array("--target-dir", args(0)),
      Array("--split-verilog", "--disable-all-randomization", "--strip-debug-info"))
  }
}
