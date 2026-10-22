// OpenRTL Lab | JPEG XL | version 1.0.0
// SPDX-License-Identifier: MIT
// SPDX-License-Identifier: MIT
package openimage.jpegxl
import chisel3._
import chisel3.util._

case class GrammarVector(alphabet: Int, bits: Seq[Int], lengths: Seq[Int], reject: Boolean = false)
object GrammarVectors {
  class WireBits {
    val bits = scala.collection.mutable.ArrayBuffer[Int]()
    def put(n: Int, value: Int): Unit = { require(value >= 0 && value < (1 << n));
      for (i <- 0 until n) bits += ((value >> i) & 1) }
  }
  def simple(a: Int, symbols: Seq[Int], shape: Int = 0): GrammarVector = {
    val b = new WireBits
    b.put(2, 1); b.put(2, symbols.size-1)
    val n = 32-Integer.numberOfLeadingZeros(a-1)
    symbols.foreach(s => b.put(n, s))
    if (symbols.size == 4) b.put(1, shape)
    val sizes = symbols.size match {
      case 1 => Seq(1)
      case 2 => Seq(1, 1)
      case 3 => Seq(1, 2, 2)
      case 4 => if (shape == 0) Seq(2, 2, 2, 2) else Seq(1, 2, 3, 3)
    }
    val lengths = Array.fill(a)(0)
    symbols.zip(sizes).foreach { case (s, l) => lengths(s) = l }
    GrammarVector(a, b.bits.toSeq, lengths.toSeq, symbols.distinct.size != symbols.size)
  }
  val order = Seq(1,2,3,4,0,5,17,6,16,7,8,9,10,11,12,13,14,15)
  def fixed(b: WireBits, n: Int): Unit = {
    val (size, value) = Map(0 -> (2,0), 4 -> (2,1), 3 -> (2,2),
      2 -> (3,3), 1 -> (4,7), 5 -> (4,15))(n)
    b.put(size, value)
  }
  def uniform: GrammarVector = {
    val b = new WireBits; b.put(2, 0)
    order.foreach(s => fixed(b, if (s == 4) 1 else 0))
    GrammarVector(16, b.bits.toSeq, Seq.fill(16)(4))
  }
  def repeats(beyond: Boolean): GrammarVector = {
    val b = new WireBits; b.put(2, 0)
    order.take(9).foreach(s => fixed(b, if (Set(0,2,16,17)(s)) 2 else 0))
    b.put(2, 3); b.put(3, if (beyond) 7 else 0) // symbol17: three leading zeros, or invalid ten
    if (!beyond) {
      b.put(2, 2) // symbol2, canonical 01, wire low-bit-first 10
      b.put(2, 1); b.put(2, 0) // symbol16, repeat previous three times
    }
    GrammarVector(8, b.bits.toSeq, Seq(0,0,0,2,2,2,2,0), beyond)
  }
  def emptyCL: GrammarVector = {
    val b = new WireBits; b.put(2, 0); order.foreach(_ => fixed(b, 0))
    GrammarVector(8, b.bits.toSeq, Seq.empty, reject=true)
  }
  val all = Seq(GrammarVector(1, Seq.empty, Seq(1)), simple(5, Seq(3)),
    simple(4, Seq(3,1)), simple(7, Seq(5,0,3)), simple(8, Seq(7,2,5,1)),
    simple(8, Seq(7,2,5,1), 1), uniform, repeats(false), simple(4, Seq(1,1)),
    repeats(true), emptyCL)
}

class PrefixGrammarTest(negative: Boolean = false) extends Module {
  val io = IO(new Bundle { val finished = Output(Bool()) })
  val vectors = GrammarVectors.all
  val packets = vectors.map { v =>
    val padded = v.bits ++ Seq.fill((8-v.bits.size%8)%8)(0)
    if (padded.isEmpty) Seq(0) else padded.grouped(8).map(g => g.zipWithIndex.map {case(b,i)=>b << i}.sum).toSeq
  }
  val flat = packets.flatten
  val expected = vectors.flatMap(v => if (v.reject) Seq.fill(v.alphabet)(0) else v.lengths)
  val bytesRom = VecInit(flat.map(_.U(8.W)))
  val lengthsRom = VecInit(expected.map(_.U(4.W)))
  val offsets = VecInit(vectors.indices.map(i => packets.take(i).map(_.size).sum.U(12.W)))
  val lengthOffsets = VecInit(vectors.indices.map(i => vectors.take(i).map(_.alphabet).sum.U(12.W)))
  val sizes = VecInit(vectors.map(_.alphabet.U(10.W)))
  val byteSizes = VecInit(packets.map(_.size.U(12.W)))
  val bitSizes = VecInit(vectors.map(_.bits.size.U(32.W)))
  val rejects = VecInit(vectors.map(_.reject.B))
  val clear :: begin :: running :: complete :: Nil = Enum(4)
  val state = RegInit(clear); val task = RegInit(0.U(4.W))
  val byteN = RegInit(0.U(12.W)); val lengthN = RegInit(0.U(10.W))
  val cycle = RegInit(0.U(16.W)); cycle := cycle+1.U
  val reader = Module(new BitReader)
  val parser = Module(new PrefixParser)
  parser.io.request <> reader.io.request
  parser.io.response <> reader.io.response
  reader.io.clear := state === clear
  reader.io.input.valid := state =/= clear && state =/= complete && byteN < byteSizes(task)
  reader.io.input.bits.data := bytesRom((offsets(task)+byteN)(log2Ceil(flat.size)-1, 0))
  reader.io.input.bits.last := byteN === byteSizes(task)-1.U
  parser.io.begin.valid := state === begin
  parser.io.begin.bits := sizes(task)
  parser.io.length.ready := cycle % 4.U =/= 0.U
  when(reader.io.input.fire) { byteN := byteN+1.U }
  when(state === clear) { byteN := 0.U; lengthN := 0.U; state := begin }
  when(parser.io.begin.fire) { state := running }
  when(state === running) {
    assert(!reader.io.failed, "PREFIX_GRAMMAR_UNEXPECTED_EOF")
    when(parser.io.failed || parser.io.done) {
      assert(parser.io.failed === rejects(task), "PREFIX_GRAMMAR_STATUS")
      when(!rejects(task)) {
        assert(lengthN === sizes(task) && reader.io.consumed === bitSizes(task), "PREFIX_GRAMMAR_COUNTS")
      }
      printf(p"PREFIX_GRAMMAR_PASS ${task} reject=${rejects(task)} lengths=${lengthN} bits=${reader.io.consumed}\n")
      when(task === (vectors.size-1).U) { state := complete }
        .otherwise { task := task+1.U; state := clear }
    }
  }
  when(parser.io.length.fire) {
    assert(state === running && !rejects(task), "PREFIX_GRAMMAR_LENGTH_ORDER")
    val wanted = lengthsRom((lengthOffsets(task)+lengthN)(log2Ceil(expected.size)-1, 0))
    if (negative) { assert(parser.io.length.bits === (wanted ^ 1.U), "NEGATIVE_GRAMMAR_EXPECTED") }
      else { assert(parser.io.length.bits === wanted, "PREFIX_GRAMMAR_LENGTH") }
    lengthN := lengthN+1.U
  }
  val stalled = RegNext(parser.io.length.valid && !parser.io.length.ready, false.B)
  val held = RegNext(parser.io.length.bits)
  when(stalled) { assert(parser.io.length.valid && parser.io.length.bits === held, "PREFIX_LENGTH_STABLE") }
  io.finished := state === complete
}
