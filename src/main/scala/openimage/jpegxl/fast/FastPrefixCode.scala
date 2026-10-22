// OpenRTL Lab | JPEG XL | version 1.0.0
// SPDX-License-Identifier: MIT
// SPDX-License-Identifier: MIT
package openimage.jpegxl.fast

import chisel3._
import chisel3.util._

class FastPrefixInput extends Bundle {
  val tag = UInt(32.W)
  val color = Bool()
  val raw = Vec(19, UInt(18.W))
  val lz = Vec(33, UInt(18.W))
}
class FastPrefixResult extends Bundle {
  val tag = UInt(32.W)
  val rawLengths = Vec(19, UInt(4.W))
  val rawBits = Vec(19, UInt(8.W))
  val lzLengths = Vec(33, UInt(4.W))
  val lzBits = Vec(33, UInt(16.W))
}

/** Two dependent exact solves per channel; canonical code construction parallel.
  * Not the code-length-description tree, header or whole encoding subsystem.
  */
class FastPrefixChannel extends RawModule {
  val clock = IO(Input(Clock())); val rst_n = IO(Input(Bool()))
  val io = IO(new Bundle {
    val input = Flipped(Decoupled(new FastPrefixInput))
    val output = Decoupled(new FastPrefixResult)
  })
  withClockAndReset(clock, (!rst_n).asAsyncReset) {
    val idle :: mergeStart :: mergeWait :: level1Send :: level1Wait :: level2Send :: level2Wait :: bases :: baseWait :: codes :: done :: Nil = Enum(11)
    val state = RegInit(idle)
    val command = Reg(new FastPrefixInput)
    val result = Reg(new FastPrefixResult)
    val escape = Reg(UInt(4.W))
    val nextCodes = Wire(Vec(16, UInt(16.W)))
    val numRaw = Mux(command.color, 11.U, 10.U)
    val solver = Module(new FastLengthSolver)
    solver.clock := clock; solver.rst_n := rst_n
    io.input.ready := rst_n && state === idle
    io.output.valid := rst_n && state === done
    io.output.bits := result
    when(io.output.fire) { state := idle }
    when(io.input.fire) {
      command := io.input.bits
      result := 0.U.asTypeOf(result); result.tag := io.input.bits.tag
      for (s <- 0 until 19) {
        when(s.U < Mux(io.input.bits.color, 11.U, 10.U)) {
          assert(io.input.bits.raw(s) =/= 0.U, "FAST_PREFIX_RAW_NONZERO")
        }.otherwise { assert(io.input.bits.raw(s) === 0.U, "FAST_PREFIX_RAW_TAIL") }
      }
      state := mergeStart
    }
    val (sumLz, mergedValid) = FastRetiming.tree(command.lz.toSeq,
      state === mergeStart, 24, "commandFrequencyTree")(_ + _)
    when(state === mergeStart) { state := mergeWait }
    when(state === mergeWait && mergedValid) { state := level1Send }
    solver.io.command.valid := state === level1Send || state === level2Send
    solver.io.command.bits.tag := command.tag
    for (s <- 0 until 33) {
      solver.io.command.bits.minimum(s) := 0.U
      val max1 = (if (s <= 10) 7 else if (s == 11) 10 else 0).U
      solver.io.command.bits.maximum(s) := Mux(state === level1Send, max1, 15.U - escape)
      val raw = if (s < 19) command.raw(s) else 0.U
      solver.io.command.bits.frequencies(s) := Mux(state === level1Send,
        Mux(s.U === numRaw, sumLz, Mux(s.U < numRaw, raw, 0.U)), command.lz(s))
    }
    when(state === level1Send) { assert(sumLz < 262144.U, "FAST_PREFIX_MERGED_FREQUENCY") }
    solver.io.output.ready := state === level1Wait || state === level2Wait
    when(solver.io.command.fire) {
      state := Mux(state === level1Send, level1Wait, level2Wait)
    }
    when(solver.io.output.fire) {
      assert(solver.io.output.bits.tag === command.tag, "FAST_PREFIX_SOLVER_TAG")
      when(state === level1Wait) {
        escape := solver.io.output.bits.lengths(numRaw.pad(6))
        for (s <- 0 until 19) result.rawLengths(s) :=
          Mux(s.U < numRaw, solver.io.output.bits.lengths(s), 0.U)
        state := level2Send
      }.otherwise {
        for (s <- 0 until 33) result.lzLengths(s) :=
          Mux(command.lz(s) =/= 0.U, solver.io.output.bits.lengths(s) + escape, 0.U)
        state := bases
      }
    }
    val countLaunch = RegNext(state === bases, false.B)
    val counts = (0 until 16).map { length =>
      val matches = RegEnable(VecInit(
        (0 until 19).map(s => s.U < numRaw && result.rawLengths(s) === length.U) ++
        (0 until 33).map(s => result.lzLengths(s) === length.U)), state === bases)
        .suggestName(s"baseLengthMatches$length")
      FastRetiming.tree(matches.toSeq.map(_.asUInt), countLaunch, 7,
        s"baseCountTree${length}_")(_ + _)
    }
    val baseValid = (0 until 16).map { length =>
      val terms = (0 until 16).map(k =>
        if (k < length) counts(k)._1 << (length-k) else 0.U(16.W))
      val base = FastRetiming.tree(terms, counts.head._2, 16, s"base${length}WeightedTree")(_ + _)
      nextCodes(length) := base._1
      base._2
    }
    when(state === bases) { state := baseWait }
    when(state === baseWait && baseValid.head) { state := codes }
    when(state === codes) {
      for (s <- 0 until 19) {
        val length = result.rawLengths(s)
        val rank = if (s == 0) 0.U else PopCount((0 until s).map(k => result.rawLengths(k) === length))
        val canonical = (nextCodes(length) + rank)(15, 0)
        val bits = Reverse(canonical) >> (16.U - length)
        result.rawBits(s) := Mux(s.U < numRaw, bits, 0.U)
      }
      for (s <- 0 until 33) {
        val length = result.lzLengths(s)
        val beforeRaw = PopCount((0 until 19).map(k => k.U < numRaw && result.rawLengths(k) === length))
        val beforeLz = if (s == 0) 0.U else PopCount((0 until s).map(k => result.lzLengths(k) === length))
        val canonical = (nextCodes(length) + beforeRaw + beforeLz)(15, 0)
        result.lzBits(s) := Mux(length === 0.U, 0.U, Reverse(canonical) >> (16.U - length))
      }
      state := done
    }
  }
}

class FastPrefixGroupResult extends Bundle {
  val tag = UInt(32.W)
  val channels = UInt(3.W)
  val tables = Vec(4, new FastPrefixResult)
}
/** Four actual independent solvers. No time-multiplexed channel engine. */
class FastPrefixGroup extends RawModule {
  val clock = IO(Input(Clock())); val rst_n = IO(Input(Bool()))
  val io = IO(new Bundle {
    val input = Flipped(Decoupled(new FastStatisticsResult))
    val output = Decoupled(new FastPrefixGroupResult)
  })
  withClockAndReset(clock, (!rst_n).asAsyncReset) {
    val channels = Reg(UInt(3.W)); val tag = Reg(UInt(32.W))
    val active = RegInit(false.B)
    val engines = Seq.fill(4)(Module(new FastPrefixChannel))
    val allReady = engines.map(_.io.input.ready).reduce(_ && _)
    val allValid = engines.map(_.io.output.valid).reduce(_ && _)
    io.input.ready := rst_n && !active && allReady
    io.output.valid := rst_n && active && allValid
    io.output.bits.tag := tag; io.output.bits.channels := channels
    for ((engine, c) <- engines.zipWithIndex) {
      engine.clock := clock; engine.rst_n := rst_n
      engine.io.input.valid := io.input.valid && !active && allReady
      engine.io.input.bits.tag := io.input.bits.tag
      engine.io.input.bits.color := io.input.bits.channels >= 3.U
      engine.io.input.bits.raw := io.input.bits.raw(c)
      engine.io.input.bits.lz := io.input.bits.lz(c)
      engine.io.output.ready := active && allValid && io.output.ready
      io.output.bits.tables(c) := engine.io.output.bits
      when(io.output.fire) { assert(engine.io.output.bits.tag === tag, "FAST_PREFIX_GROUP_TAG") }
    }
    when(io.input.fire) { channels := io.input.bits.channels; tag := io.input.bits.tag; active := true.B }
    when(io.output.fire) { active := false.B }
  }
}
