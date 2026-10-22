// OpenRTL Lab | JPEG XL | version 1.0.0
// SPDX-License-Identifier: MIT
// SPDX-License-Identifier: MIT
package openimage.jpegxl.fast

import chisel3._
import chisel3.util._

class FastStatisticsCommand extends Bundle {
  val context = Bool()
  val tag = UInt(32.W)
  val channels = UInt(3.W)
  val empty = Bool()
}

class FastStatisticsResult extends Bundle {
  val context = Bool()
  val tag = UInt(32.W)
  val channels = UInt(3.W)
  val samples = UInt(10.W)
  val raw = Vec(4, Vec(19, UInt(18.W)))
  val lz = Vec(4, Vec(33, UInt(18.W)))
}

/** Parallel per-bin reductions prevent same-symbol lane write collisions.
  * Only the approved effort=1 single-group sampling profile (<=512 samples).
  * This module is not the code-length solver or a complete encoder.
  */
class FastStatistics extends RawModule {
  val clock = IO(Input(Clock()))
  val rst_n = IO(Input(Bool()))
  val io = IO(new Bundle {
    val command = Flipped(Decoupled(new FastStatisticsCommand))
    val input = Flipped(Decoupled(new FastRunOutput))
    val output = Decoupled(new FastStatisticsResult)
  })
  withClockAndReset(clock, (!rst_n).asAsyncReset) {
    val rawBase = Seq(3843, 852, 1270, 1214, 1014, 727, 481, 300, 159, 51,
      5, 1, 1, 1, 1, 1, 1, 1, 1)
    val lzBase = Seq(29, 27, 25, 23, 21, 21, 19, 18, 21, 17, 16, 15, 15, 14,
      13, 13, 137, 98, 61, 34, 1, 1, 1, 1, 1, 1, 1, 1, 0, 0, 0, 0, 0)
    val raw = Reg(Vec(2, Vec(4, Vec(19, UInt(10.W)))))
    val lz = Reg(Vec(2, Vec(4, Vec(33, UInt(10.W)))))
    val active = RegInit(VecInit(Seq.fill(2)(false.B)))
    val tags = Reg(Vec(2, UInt(32.W)))
    val channels = Reg(Vec(2, UInt(3.W)))
    val samples = Reg(Vec(2, UInt(10.W)))
    val output = Reg(new FastStatisticsResult)
    val valid = RegInit(false.B)
    val advance = !valid || io.output.ready
    val in = io.input.bits
    val ctx = in.input.context.asUInt
    val cmd = io.command.bits
    val cmdCtx = cmd.context.asUInt
    val completing = io.input.valid && active(ctx) && in.input.last
    io.input.ready := rst_n && active(ctx) && (!in.input.last || advance)
    io.command.ready := rst_n && !active(cmdCtx) &&
      (!cmd.empty || advance && !completing)
    io.output.valid := rst_n && valid
    io.output.bits := output
    when(advance) { valid := false.B }

    when(io.command.fire) {
      assert(cmd.channels >= 1.U && cmd.channels <= 4.U, "FAST_STATS_CHANNELS")
      active(cmdCtx) := !cmd.empty
      tags(cmdCtx) := cmd.tag
      channels(cmdCtx) := cmd.channels
      samples(cmdCtx) := 0.U
      for (c <- 0 until 4) {
        for (s <- 0 until 19) raw(cmdCtx)(c)(s) := 0.U
        for (s <- 0 until 33) lz(cmdCtx)(c)(s) := 0.U
      }
      when(cmd.empty) {
        valid := true.B
        output.context := cmd.context
        output.tag := cmd.tag
        output.channels := cmd.channels
        output.samples := 0.U
        for (c <- 0 until 4) {
          for (s <- 0 until 19) output.raw(c)(s) :=
            Mux(s.U < Mux(cmd.channels >= 3.U, 11.U, 10.U), rawBase(s).U, 0.U)
          for (s <- 0 until 33) output.lz(c)(s) := lzBase(s).U
        }
      }
    }
    when(io.input.fire) {
      val block = in.input.block
      val total = samples(ctx) +& block.count
      assert(in.input.sampling, "FAST_STATS_SAMPLING_ONLY")
      assert(block.count >= 1.U && block.count <= 8.U, "FAST_STATS_COUNT")
      assert(block.tag === tags(ctx) && block.channels === channels(ctx), "FAST_STATS_IDENTITY")
      assert(in.input.first === (samples(ctx) === 0.U), "FAST_STATS_FIRST")
      assert(total <= 512.U, "FAST_STATS_PROFILE_CAPACITY")
      samples(ctx) := total
      when(in.input.last) {
        active(ctx) := false.B
        valid := true.B
        output.context := in.input.context
        output.tag := block.tag
        output.channels := block.channels
        output.samples := total
      }
      for (c <- 0 until 4) {
        val enabled = c.U < block.channels && in.emit(c)
        val hasRun = enabled && in.run(c) =/= 0.U
        val length = in.run(c) - 8.U
        val high = 16.U(5.W) - PriorityEncoder(Reverse(length))
        val runToken = Mux(length < 16.U, length(4, 0), high + 12.U)
        when(enabled) {
          assert(in.skip(c) <= block.count, "FAST_STATS_SKIP")
          assert(in.run(c) === 0.U || in.run(c) >= 8.U, "FAST_STATS_RUN")
        }
        for (lane <- 0 until 8) {
          when(enabled && lane.U >= in.skip(c) && lane.U < block.count) {
            assert(block.token(c)(lane) < Mux(block.channels >= 3.U, 11.U, 10.U),
              "FAST_STATS_RAW_SYMBOL_RANGE")
          }
        }
        for (s <- 0 until 19) {
          val increment = PopCount((0 until 8).map(lane => enabled &&
            lane.U >= in.skip(c) && lane.U < block.count && block.token(c)(lane) === s.U)) +&
            (if (s == 0) hasRun.asUInt else 0.U)
          val updated = raw(ctx)(c)(s) +& increment
          raw(ctx)(c)(s) := updated
          assert(updated <= 512.U, "FAST_STATS_RAW_COUNT_CAPACITY")
          when(in.input.last) {
            output.raw(c)(s) := (updated << 8) +
              Mux(s.U < Mux(block.channels >= 3.U, 11.U, 10.U), rawBase(s).U, 0.U)
          }
        }
        for (s <- 0 until 33) {
          val updated = lz(ctx)(c)(s) +& (hasRun && runToken === s.U).asUInt
          lz(ctx)(c)(s) := updated
          assert(updated <= 512.U, "FAST_STATS_LZ_COUNT_CAPACITY")
          when(in.input.last) { output.lz(c)(s) := (updated << 8) + lzBase(s).U }
        }
      }
    }
  }
}
