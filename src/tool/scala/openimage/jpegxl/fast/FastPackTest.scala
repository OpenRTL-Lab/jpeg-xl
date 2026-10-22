// OpenRTL Lab | JPEG XL | version 1.0.0
// SPDX-License-Identifier: MIT
// SPDX-License-Identifier: MIT
package openimage.jpegxl.fast

import chisel3._
import chisel3.util._
import chisel3.util.experimental.loadMemoryFromFileInline
import _root_.circt.stage.ChiselStage

class ExternalFastPack extends BlackBox {
  override def desiredName = "FastSymbolPack8"
  val io = IO(new Bundle {
    val clock = Input(Clock()); val rst_n = Input(Bool())
    val io = new Bundle {
      val input = Flipped(Decoupled(new FastPackInput))
      val output = Decoupled(new FastPackedBlock)
    }
  })
}
class FastPackTest(rows: Int, file: String, negative: Boolean) extends Module {
  val io = IO(new Bundle { val finished = Output(Bool()) })
  def table(col: Int, bits: Int): Mem[UInt] = {
    val m = Mem(rows, UInt(bits.W)); loadMemoryFromFileInline(m, file + s".column-$col.hex"); m
  }
  val context = table(1, 1); val channels = table(2, 3)
  val first = table(3, 1); val last = table(4, 1); val count = table(5, 4); val tag = table(6, 32)
  val token = table(7, 160); val extraLengths = table(8, 128); val extra = table(9, 512)
  val emit = table(10, 4); val runs = table(11, 68); val skips = table(12, 16)
  val rawLengths = table(13, 304); val rawBits = table(14, 608)
  val lzLengths = table(15, 528); val lzBits = table(16, 2112)
  val bits = table(17, 1024); val lengths = table(18, 36)
  val dut = Module(new ExternalFastPack)
  val phase = RegInit(0.U(3.W)); val cycle = RegInit(0.U(32.W)); cycle := cycle + 1.U
  val si = RegInit(0.U(log2Ceil(rows + 1).W)); val ri = RegInit(0.U(log2Ceil(rows + 1).W))
  val offer = RegInit(false.B); val started = Reg(UInt(32.W))
  val sentAt = Reg(Vec(rows, UInt(32.W)))
  val running = phase === 2.U || phase === 3.U
  val index = Mux(si < rows.U, si, 0.U)
  dut.io.clock := clock; dut.io.rst_n := !reset.asBool && phase =/= 1.U
  val in = dut.io.io.input.bits
  in := 0.U.asTypeOf(in)
  in.planned.input.block.tag := tag(index)
  in.planned.input.block.channels := channels(index)
  in.planned.input.block.count := count(index)
  in.planned.input.context := context(index).asBool
  in.planned.input.first := first(index).asBool; in.planned.input.last := last(index).asBool
  in.planned.input.block.token := token(index).asTypeOf(in.planned.input.block.token)
  in.planned.input.block.extraBits := extraLengths(index).asTypeOf(in.planned.input.block.extraBits)
  in.planned.input.block.extra := extra(index).asTypeOf(in.planned.input.block.extra)
  in.planned.emit := emit(index).asTypeOf(in.planned.emit)
  in.planned.run := runs(index).asTypeOf(in.planned.run)
  in.planned.skip := skips(index).asTypeOf(in.planned.skip)
  val rawLengthTable = rawLengths(index).asTypeOf(Vec(4, Vec(19, UInt(4.W))))
  val rawBitTable = rawBits(index).asTypeOf(Vec(4, Vec(19, UInt(8.W))))
  val lzLengthTable = lzLengths(index).asTypeOf(Vec(4, Vec(33, UInt(4.W))))
  val lzBitTable = lzBits(index).asTypeOf(Vec(4, Vec(33, UInt(16.W))))
  for (c <- 0 until 4) {
    in.tables(c).tag := tag(index)
    in.tables(c).rawLengths := rawLengthTable(c); in.tables(c).rawBits := rawBitTable(c)
    in.tables(c).lzLengths := lzLengthTable(c); in.tables(c).lzBits := lzBitTable(c)
  }
  dut.io.io.input.valid := phase === 0.U && cycle < 4.U ||
    running && si < rows.U && (phase === 2.U || offer)
  dut.io.io.output.ready := running && (phase === 2.U || cycle % 13.U >= 8.U)
  when(phase === 0.U && cycle === 5.U) { phase := 1.U }
  when(phase === 1.U) { assert(!dut.io.io.output.valid, "FAST_PACK_RESET_CANCEL") }
  when(phase === 1.U && cycle === 8.U) { phase := 2.U }
  when(phase === 3.U && si < rows.U && cycle % 5.U =/= 0.U) { offer := true.B }
  when(running && dut.io.io.input.fire) {
    sentAt(si) := cycle
    when(si === 0.U) { started := cycle }
    when(phase === 2.U && si =/= 0.U) {
      assert(cycle - started === si, "FAST_PACK_IDEAL_II_ONE")
    }
    si := si + 1.U; offer := false.B
  }
  val stall = dut.io.io.output.valid && !dut.io.io.output.ready
  val priorStall = RegNext(stall, false.B)
  val saved = RegEnable(dut.io.io.output.bits.asUInt, stall)
  when(running && priorStall) {
    assert(dut.io.io.output.valid && saved === dut.io.io.output.bits.asUInt, "FAST_PACK_OUTPUT_HOLD")
  }
  when(running && dut.io.io.output.fire) {
    val out = dut.io.io.output.bits
    assert(ri < rows.U && out.tag === tag(ri), "FAST_PACK_TAG")
    assert(out.context === context(ri).asBool && out.channels === channels(ri) &&
      out.count === count(ri) && out.first === first(ri).asBool && out.last === last(ri).asBool,
      "FAST_PACK_METADATA")
    assert(out.bits.asUInt === (if (negative) bits(ri) ^ 1.U else bits(ri)), "FAST_PACK_OFFICIAL_BITS")
    assert(out.lengths.asUInt === lengths(ri), "FAST_PACK_OFFICIAL_LENGTHS")
    when(phase === 2.U) { assert(cycle - sentAt(ri) === 2.U, "FAST_PACK_IDEAL_LATENCY_TWO") }
    printf("FAST_PACK_RESULT phase=%d index=%d bits=%x lengths=%x\n",
      phase, ri, out.bits.asUInt, out.lengths.asUInt)
    ri := ri + 1.U
    when(ri === (rows - 1).U) {
      assert(si === rows.U, "FAST_PACK_ALL_RECORDS")
      when(phase === 3.U) { printf("FAST_PACK_SUITE_PASS\n") }
      phase := phase + 1.U; si := 0.U; ri := 0.U; offer := false.B
    }
  }
  assert(cycle < 200000.U, "FAST_PACK_WATCHDOG")
  io.finished := phase === 4.U
}
object EmitFastPack {
  def main(args: Array[String]): Unit = {
    require(args.length == 1)
    ChiselStage.emitSystemVerilogFile(new FastSymbolPack8, Array("--target-dir", args(0)),
      Array("--split-verilog", "--disable-all-randomization", "--strip-debug-info"))
  }
}
object EmitFastPackTest {
  def main(args: Array[String]): Unit = {
    require(args.length == 3)
    val f = scala.io.Source.fromFile(args(1))
    val n = try f.getLines().count(_.nonEmpty) finally f.close()
    ChiselStage.emitSystemVerilogFile(new FastPackTest(n, args(1), args(2) == "negative"),
      Array("--target-dir", args(0)),
      Array("--split-verilog", "--disable-all-randomization", "--strip-debug-info"))
  }
}
