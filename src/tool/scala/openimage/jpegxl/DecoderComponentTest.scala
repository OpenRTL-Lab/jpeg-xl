// OpenRTL Lab | JPEG XL | version 1.0.0
// SPDX-License-Identifier: MIT
// SPDX-License-Identifier: MIT
package openimage.jpegxl
import chisel3._
import chisel3.util._
import _root_.circt.stage.ChiselStage

case class PrefixVector(lengths: Seq[Int], symbols: Seq[Int], reject: Boolean = false)
class DecoderComponentTest(negative: Boolean, grammarNegative: Boolean = false, streamNegative: Boolean = false,
                           streamAddress: Boolean = false, streamAddressNegative: Boolean = false) extends Module {
  val io = IO(new Bundle { val finished = Output(Bool()) })
  val grammar = Module(new PrefixGrammarTest(grammarNegative))
  val bank = Module(new BankedBytesTest)
  val stream = Module(new ReadStreamTest(streamNegative,streamAddress || streamAddressNegative,streamAddressNegative))
  val cycle = RegInit(0.U(32.W)); cycle := cycle+1.U
  assert(cycle < 100000.U, "COMPONENT_TIMEOUT")
  val reader = Module(new BitReader)
  val bytes = (0 until 16).map(i => (i*37+11) & 255)
  val widths = Seq(0, 1, 7, 16, 32, 3, 9, 31, 29)
  require(widths.sum == 128)
  val packed = bytes.zipWithIndex.foldLeft(BigInt(0)) { case (v, (b, i)) => v | (BigInt(b) << (8*i)) }
  val expected = widths.indices.map(i => (packed >> widths.take(i).sum) & ((BigInt(1) << widths(i))-1))
  val requestN = RegInit(0.U(4.W)); val responseN = RegInit(0.U(4.W))
  val byteN = RegInit(0.U(5.W)); val readerDone = RegInit(false.B)
  reader.io.clear := false.B
  reader.io.input.valid := byteN < 16.U
  reader.io.input.bits.data := VecInit(bytes.map(_.U(8.W)))(byteN(3, 0))
  reader.io.input.bits.last := byteN === 15.U
  when(reader.io.input.fire) { byteN := byteN+1.U }
  reader.io.request.valid := requestN < 10.U && !readerDone
  reader.io.request.bits := VecInit((widths :+ 1).map(_.U(6.W)))(requestN)
  when(reader.io.request.fire) { requestN := requestN+1.U }
  reader.io.response.ready := cycle % 5.U =/= 0.U
  when(reader.io.response.fire) {
    assert(responseN < 9.U, "EOF_MUST_NOT_RETURN_DATA")
    assert(reader.io.response.bits === VecInit((expected ++ Seq.fill(7)(BigInt(0))).map(_.U(32.W)))(responseN),
      "BIT_READER_DATA")
    responseN := responseN+1.U
  }
  when(reader.io.failed) {
    assert(requestN === 10.U && responseN === 9.U && reader.io.consumed === 128.U, "EOF_BOUNDARY")
    when(!readerDone) { printf("BIT_READER_PASS requests=9 eof_rejected=1\n") }
    readerDone := true.B
  }
  val readerStall = RegNext(reader.io.response.valid && !reader.io.response.ready, false.B)
  val readerBits = RegNext(reader.io.response.bits)
  when(readerStall) { assert(reader.io.response.valid && reader.io.response.bits === readerBits, "READER_STABLE") }

  val vectors = Seq(
    PrefixVector(Seq.fill(16)(4), Seq(15, 0, 7, 3, 9, 1, 12)),
    PrefixVector(Seq.fill(14)(4) ++ Seq.fill(4)(5), Seq(0,13,14,15,16,17)),
    PrefixVector(Seq.fill(14)(4).updated(0,5) ++ Seq.fill(4)(5).updated(0,4), Seq(0,13,14,15,16,17)),
    PrefixVector(Seq(0, 0, 5, 0), Seq(2, 2, 2)),
    PrefixVector((1 to 14) ++ Seq(15, 15), Seq(15, 14, 0, 12, 8, 1)),
    PrefixVector(Seq(1, 1), Seq(1, 0, 1, 1)),
    PrefixVector(Seq(1, 1, 1), Seq.empty, reject=true),
    PrefixVector(Seq(2, 2), Seq.empty, reject=true),
    PrefixVector(Seq(0, 0, 0), Seq.empty, reject=true),
    PrefixVector(Seq.fill(512)(9), Seq(511, 0, 256, 17, 500)))
  def encoded(v: PrefixVector): Seq[Int] = {
    if (v.reject || v.lengths.count(_ != 0) == 1) Seq.empty
    else {
      val count = (0 to 15).map(n => if (n == 0) 0 else v.lengths.count(_ == n))
      val next = Array.fill(16)(0)
      for (n <- 1 to 15) next(n) = (next(n-1)+count(n-1)) << 1
      val codes = v.lengths.map { n => if (n == 0) 0 else { val c = next(n); next(n) += 1; c } }
      v.symbols.flatMap(s => (0 until v.lengths(s)).reverse.map(i => (codes(s) >> i) & 1))
    }
  }
  val coded = vectors.map(encoded)
  val flatLengths = vectors.flatMap(_.lengths)
  val flatBits = coded.flatten ++ Seq(0)
  val flatSymbols = vectors.flatMap(_.symbols) ++ Seq(0)
  val lengthsRom = VecInit(flatLengths.map(_.U(4.W)))
  val bitRom = VecInit(flatBits.map(_.B))
  val symbolRom = VecInit(flatSymbols.map(_.U(9.W)))
  val lengthOffsets = VecInit(vectors.indices.map(i => vectors.take(i).map(_.lengths.size).sum.U(12.W)))
  val bitOffsets = VecInit(vectors.indices.map(i => coded.take(i).map(_.size).sum.U(12.W)))
  val symbolOffsets = VecInit(vectors.indices.map(i => vectors.take(i).map(_.symbols.size).sum.U(12.W)))
  val sizes = VecInit(vectors.map(_.lengths.size.U(10.W)))
  val symbolSizes = VecInit(vectors.map(_.symbols.size.U(5.W)))
  val rejected = VecInit(vectors.map(_.reject.B))
  val table = Module(new CanonicalTable)
  val begin :: load :: waitBuild :: launch :: transfer :: advance :: complete :: Nil = Enum(7)
  val state = RegInit(begin); val task = RegInit(0.U(4.W))
  val lengthN = RegInit(0.U(10.W)); val bitN = RegInit(0.U(12.W)); val symbolN = RegInit(0.U(5.W))
  table.io.begin.valid := state === begin
  table.io.begin.bits := sizes(task)
  table.io.length.valid := state === load
  table.io.length.bits := lengthsRom((lengthOffsets(task)+lengthN)(log2Ceil(flatLengths.size)-1, 0))
  table.io.decode.valid := state === launch
  table.io.decode.bits := true.B
  table.io.bit.valid := state === transfer
  table.io.bit.bits := bitRom((bitOffsets(task)+bitN)(log2Ceil(flatBits.size)-1, 0))
  table.io.symbol.ready := cycle % 7.U =/= 0.U
  when(table.io.begin.fire) { state := load }
  when(table.io.length.fire) {
    assert(!table.io.fast18,"FAST_TABLE_NOT_READY_DURING_LOAD")
    when(lengthN === sizes(task)-1.U) { state := waitBuild }
      .otherwise { lengthN := lengthN+1.U }
  }
  when(state === waitBuild) {
    when(table.io.failed) { assert(rejected(task), "UNEXPECTED_TREE_REJECTION"); state := advance }
    .elsewhen(table.io.built) {
      assert(!rejected(task), "ILLEGAL_TREE_ACCEPTED")
      assert(table.io.fast18 === (task === 1.U),"FAST_TABLE_LENGTH_ATTRIBUTE")
      state := launch
    }
  }
  when(table.io.decode.fire) { state := transfer }
  when(table.io.bit.fire) { bitN := bitN+1.U }
  when(table.io.symbol.fire) {
    assert(state === transfer, "SYMBOL_ORDER")
    val wanted = symbolRom((symbolOffsets(task)+symbolN)(log2Ceil(flatSymbols.size)-1, 0))
    if (negative) { assert(table.io.symbol.bits === (wanted ^ 1.U), "NEGATIVE_PREFIX_EXPECTED") }
      else { assert(table.io.symbol.bits === wanted, "PREFIX_SYMBOL") }
    when(symbolN === symbolSizes(task)-1.U) { state := advance }
      .otherwise { symbolN := symbolN+1.U; state := launch }
  }
  val stalled = RegNext(table.io.symbol.valid && !table.io.symbol.ready, false.B)
  val held = RegNext(table.io.symbol.bits)
  when(stalled) { assert(table.io.symbol.valid && table.io.symbol.bits === held, "SYMBOL_STABLE") }
  when(state === advance) {
    printf(p"CANONICAL_TASK_PASS ${task} reject=${rejected(task)} symbols=${symbolSizes(task)}\n")
    when(task === (vectors.size-1).U) { state := complete }
      .otherwise { task := task+1.U; lengthN := 0.U; bitN := 0.U; symbolN := 0.U; state := begin }
  }
  val all = readerDone && state === complete && grammar.io.finished && bank.io.finished && stream.io.finished
  when(all && !RegNext(all, false.B)) { printf("DECODER_COMPONENT_SUITE_PASS\n") }
  io.finished := RegNext(all, false.B)
}

object EmitDecoderComponentTest {
  def main(args: Array[String]): Unit = {
    require(args.length == 2)
    require(Set("normal","negative","grammar-negative","stream-negative","stream-address","stream-address-negative").contains(args(1)))
    ChiselStage.emitSystemVerilogFile(new DecoderComponentTest(args(1) == "negative", args(1) == "grammar-negative", args(1) == "stream-negative",
      args(1) == "stream-address",args(1) == "stream-address-negative"),
      Array("--target-dir", args(0)), Array("--split-verilog", "--disable-all-randomization", "--strip-debug-info"))
  }
}
