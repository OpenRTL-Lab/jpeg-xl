// OpenRTL Lab | JPEG XL | version 1.0.0
// SPDX-License-Identifier: MIT
// SPDX-License-Identifier: MIT
package openimage.jpegxl.fast

import chisel3._
import chisel3.util._

class FastHeaderResult extends Bundle {
  val tag = UInt(32.W)
  val bits = UInt(512.W)
  val length = UInt(10.W)
}
class FastHeaderGroupResult extends Bundle {
  val tag = UInt(32.W); val channels = UInt(3.W)
  val headers = Vec(4, new FastHeaderResult)
}
/** Exact official WriteTo, separate small solver and parallel segment trees. */
class FastHeaderChannel extends RawModule {
  val clock = IO(Input(Clock())); val rst_n = IO(Input(Bool()))
  val io = IO(new Bundle {
    val input = Flipped(Decoupled(new FastPrefixResult))
    val output = Decoupled(new FastHeaderResult)
  })
  withClockAndReset(clock, (!rst_n).asAsyncReset) {
    def highest(xs: Seq[UInt]): UInt = {
      if (xs.size == 1) xs.head
      else highest(xs.grouped(2).map(x => if (x.size == 1) x.head
        else Mux(x(0) >= x(1), x(0), x(1))).toSeq)
    }
    def concatenate(xs: Seq[(UInt, UInt)], width: Int, lengthWidth: Int): (UInt, UInt) = {
      if (xs.size == 1) xs.head
      else concatenate(xs.grouped(2).map { p =>
        if (p.size == 1) p.head else
          (((p(1)._1 << p(0)._2) | p(0)._1)(width - 1, 0),
            (p(0)._2 +& p(1)._2)(lengthWidth - 1, 0))
      }.toSeq, width, lengthWidth)
    }
    val idle :: frequencyStart :: frequencyWait :: send :: waiting :: bases :: baseWait :: prepare :: chunks :: pack :: done :: Nil = Enum(11)
    val state = RegInit(idle)
    val command = Reg(new FastPrefixResult)
    val result = Reg(new FastHeaderResult)
    val numLz = Reg(UInt(6.W)); val numCodeLengths = Reg(UInt(5.W))
    val codeLengths = Reg(Vec(18, UInt(4.W)))
    val nextCodes = Wire(Vec(6, UInt(16.W)))
    val segmentBits = Reg(Vec(77, UInt(16.W))); val segmentLengths = Reg(Vec(77, UInt(4.W)))
    val chunkBits = Reg(Vec(10, UInt(64.W))); val chunkLengths = Reg(Vec(10, UInt(6.W)))
    val order = Seq(1, 2, 3, 4, 0, 5, 17, 6, 16, 7, 8, 9, 10, 11, 12, 13, 14, 15)
    val solver = Module(new FastLengthSolver(32, 5))
    solver.clock := clock; solver.rst_n := rst_n
    io.input.ready := rst_n && state === idle
    io.output.valid := rst_n && state === done
    io.output.bits := result
    when(io.output.fire) { state := idle }
    when(io.input.fire) {
      command := io.input.bits
      result := 0.U.asTypeOf(result); result.tag := io.input.bits.tag
      val last = highest((0 until 33).map(i => Mux(io.input.bits.lzLengths(i) =/= 0.U, (i + 1).U, 0.U)))
      assert(last =/= 0.U, "FAST_HEADER_NONEMPTY_LZ")
      numLz := last; state := frequencyStart
    }
    val countLaunch = RegNext(state === frequencyStart, false.B)
    val commandCounts = (0 until 16).map { length =>
      val matches = RegEnable(VecInit(command.rawLengths.map(_ === length.U) ++
        command.lzLengths.map(_ === length.U)), state === frequencyStart)
        .suggestName(s"commandLengthMatches$length")
      FastRetiming.tree(matches.toSeq.map(_.asUInt), countLaunch, 7,
        s"commandFrequencyTree${length}_")(_ + _)
    }
    when(state === frequencyStart) { state := frequencyWait }
    when(state === frequencyWait && commandCounts.head._2) { state := send }
    solver.io.command.valid := state === send
    solver.io.command.bits.tag := command.tag
    for (i <- 0 until 33) {
      solver.io.command.bits.minimum(i) := 0.U
      solver.io.command.bits.maximum(i) := (if (i < 18) 5 else 0).U
      val count = if (i < 16) commandCounts(i)._1 else (if (i == 17) 67 else 0).U
      solver.io.command.bits.frequencies(i) := count
    }
    solver.io.output.ready := state === waiting
    when(solver.io.command.fire) { state := waiting }
    when(solver.io.output.fire) {
      assert(solver.io.output.bits.tag === command.tag, "FAST_HEADER_SOLVER_TAG")
      for (i <- 0 until 18) codeLengths(i) := solver.io.output.bits.lengths(i)
      state := bases
    }
    val baseCountLaunch = RegNext(state === bases, false.B)
    val counts = (0 until 6).map { length =>
      val matches = RegEnable(VecInit(codeLengths.map(_ === length.U)), state === bases)
        .suggestName(s"baseLengthMatches$length")
      FastRetiming.tree(matches.toSeq.map(_.asUInt), baseCountLaunch, 5,
        s"baseCountTree${length}_")(_ + _)
    }
    val lastCode = FastRetiming.tree((0 until 18).map(j =>
      Mux(codeLengths(order(j)) =/= 0.U, (j+1).U(5.W), 0.U(5.W))),
      state === bases, 5, "lastCodeTree")((a,b) => Mux(a >= b,a,b))
    when(lastCode._2) { numCodeLengths := lastCode._1 }
    val baseValid = (0 until 6).map { length =>
      val terms = (0 until 8).map(k =>
        if (k < length) counts(k)._1 << (length-k) else 0.U(16.W))
      val base = FastRetiming.tree(terms, counts.head._2, 16, s"base${length}WeightedTree")(_ + _)
      nextCodes(length) := base._1
      base._2
    }
    when(state === bases) { state := baseWait }
    when(state === baseWait && baseValid.head) { state := prepare }
    val codeBits = Wire(Vec(18, UInt(16.W)))
    for (i <- 0 until 18) {
      val length = codeLengths(i)
      val lengthIndex = Wire(UInt(3.W)); lengthIndex := length
      val rank = if (i == 0) 0.U else PopCount((0 until i).map(k => codeLengths(k) === length))
      val canonical = (nextCodes(lengthIndex) + rank)(15, 0)
      codeBits(i) := Mux(length === 0.U, 0.U, Reverse(canonical) >> (16.U - length))
    }
    def index5(value: UInt): UInt = {
      val index = Wire(UInt(5.W)); index := value; index
    }
    when(state === prepare) {
      assert(numCodeLengths =/= 0.U && codeLengths(17) >= 1.U && codeLengths(17) <= 5.U,
        "FAST_HEADER_DESCRIPTOR_CODE")
      segmentBits(0) := 0.U; segmentLengths(0) := 2.U
      val fixedLengths = VecInit(Seq(2, 4, 3, 2, 2, 4).map(_.U(4.W)))
      val fixedBits = VecInit(Seq(0, 7, 3, 2, 1, 15).map(_.U(16.W)))
      for (j <- 0 until 18) {
        val symbol = codeLengths(order(j))
        val smallIndex = Wire(UInt(3.W)); smallIndex := symbol
        assert(symbol <= 5.U, "FAST_HEADER_MAX_CODE_LENGTH")
        segmentBits(1 + j) := Mux(j.U < numCodeLengths, fixedBits(smallIndex), 0.U)
        segmentLengths(1 + j) := Mux(j.U < numCodeLengths, fixedLengths(smallIndex), 0.U)
      }
      for (j <- 0 until 19) {
        val symbol = index5(command.rawLengths(j))
        segmentBits(19 + j) := codeBits(symbol)
        segmentLengths(19 + j) := codeLengths(symbol)
      }
      for (j <- 0 until 3) {
        segmentBits(38 + 2 * j) := codeBits(17)
        segmentLengths(38 + 2 * j) := codeLengths(17)
        segmentBits(39 + 2 * j) := (if (j == 1) 0 else 2).U
        segmentLengths(39 + 2 * j) := 3.U
      }
      for (j <- 0 until 33) {
        val symbol = index5(command.lzLengths(j))
        segmentBits(44 + j) := Mux(j.U < numLz, codeBits(symbol), 0.U)
        segmentLengths(44 + j) := Mux(j.U < numLz, codeLengths(symbol), 0.U)
      }
      state := chunks
    }
    when(state === chunks) {
      for (chunk <- 0 until 10) {
        val joined = concatenate((chunk * 8 until math.min(chunk * 8 + 8, 77)).map(i =>
          (segmentBits(i).pad(64), segmentLengths(i).pad(6))), 64, 6)
        assert(joined._2 <= 40.U, "FAST_HEADER_CHUNK_CAPACITY")
        chunkBits(chunk) := joined._1; chunkLengths(chunk) := joined._2
      }
      state := pack
    }
    when(state === pack) {
      val joined = concatenate((0 until 10).map(i =>
        (chunkBits(i).pad(512), chunkLengths(i).pad(10))), 512, 10)
      assert(joined._2 <= 358.U, "FAST_HEADER_CAPACITY")
      result.bits := joined._1; result.length := joined._2; state := done
    }
  }
}

class FastHeaderGroup extends RawModule {
  val clock = IO(Input(Clock())); val rst_n = IO(Input(Bool()))
  val io = IO(new Bundle {
    val input = Flipped(Decoupled(new FastPrefixGroupResult))
    val output = Decoupled(new FastHeaderGroupResult)
  })
  withClockAndReset(clock, (!rst_n).asAsyncReset) {
    val active = RegInit(false.B)
    val tag = Reg(UInt(32.W)); val channels = Reg(UInt(3.W))
    val engines = Seq.fill(4)(Module(new FastHeaderChannel))
    val allReady = engines.map(_.io.input.ready).reduce(_ && _)
    val allValid = engines.map(_.io.output.valid).reduce(_ && _)
    io.input.ready := rst_n && !active && allReady
    io.output.valid := rst_n && active && allValid
    io.output.bits.tag := tag; io.output.bits.channels := channels
    for ((engine, c) <- engines.zipWithIndex) {
      engine.clock := clock; engine.rst_n := rst_n
      engine.io.input.valid := io.input.valid && !active && allReady
      engine.io.input.bits := io.input.bits.tables(c)
      engine.io.output.ready := active && allValid && io.output.ready
      io.output.bits.headers(c) := engine.io.output.bits
      when(io.input.fire) { assert(io.input.bits.tables(c).tag === io.input.bits.tag, "FAST_HEADER_INPUT_TAG") }
      when(io.output.fire) { assert(engine.io.output.bits.tag === tag, "FAST_HEADER_OUTPUT_TAG") }
    }
    when(io.input.fire) { tag := io.input.bits.tag; channels := io.input.bits.channels; active := true.B }
    when(io.output.fire) { active := false.B }
  }
}
